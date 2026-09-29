// =============================================================================
// APTUIDSH · 「内置 dsh 升级」对照面快照
// -----------------------------------------------------------------------------
// 用法: node snapshot.mjs <node_modules/@deepseek-ai 路径> <输出 json>
//
// 目的：把「升级会踩到的东西」变成机器可比的清单，而不是靠回忆。
// 抽的都是**本项目补丁/垫片直接依赖的签名** —— dsh 一改，这些就失配：
//   1. 方法表        → 0.1.5→0.1.7 就删过 subagents/list（协议层破坏性变更）
//   2. 设置页 CSS 前缀 → 窄屏覆盖层用 [class~="VOzbGW_panel"] 选它
//   3. boot 内核标记  → BOOT_SETTLE 补丁挂在这条链上
//   4. protocolOf     → URL authority 垫片服务的就是它
//   5. engines / 原生包 → 内置 Node 版本与 arm64 原生二进制
// =============================================================================
import fs from 'node:fs';
import path from 'node:path';

const [, , ROOT, OUT] = process.argv;
if (!ROOT || !OUT) { console.error('用法: node snapshot.mjs <@deepseek-ai 路径> <输出 json>'); process.exit(1); }
const read = (p) => { try { return fs.readFileSync(p, 'utf8'); } catch { return null; } };

/**
 * 解析出真正的「官方包目录」。
 *
 * 两种安装布局差别很大，指错了会得到**看起来像灾难**的结论：
 *   · 本地安装  → <root>/@deepseek-ai/ 下直接是 289 个官方包
 *   · 全局安装  → <root>/@deepseek-ai/ 下只有 dsh，官方包嵌在
 *                 dsh/node_modules/@deepseek-ai/
 * 而本项目的生产环境（rootfs 里的 /usr/local）正是**全局布局**。若不做这一步，
 * 对着生产安装抽快照只会得到「1 个包、0 个方法」，diff 出来就是
 * 「119 个方法全被删除、CSS 特征串全没了」——全是假警报。
 */
function resolveRoot(root) {
  try {
    const isPkg = (n) => fs.existsSync(path.join(root, n, 'package.json'));
    const top = fs.readdirSync(root).filter(isPkg);
    const nested = path.join(root, 'dsh', 'node_modules', '@deepseek-ai');
    if (top.length <= 2 && fs.existsSync(nested)) return { root: nested, layout: 'global(嵌套)' };
    return { root, layout: 'local(平铺)' };
  } catch { return { root, layout: '未知' }; }
}
const RESOLVED = resolveRoot(ROOT);
const SCAN = RESOLVED.root;
const uniq = (a) => [...new Set(a)].sort();
const grab = (src, re) => (src ? uniq([...src.matchAll(re)].map((m) => m[0])) : []);

// ---- 1. 全包版本表 ---------------------------------------------------------
const versions = {};
const pluginIds = [];
for (const d of fs.readdirSync(SCAN, { withFileTypes: true })) {
  if (!d.isDirectory()) continue;
  const pj = path.join(SCAN, d.name, 'package.json');
  if (!fs.existsSync(pj)) continue;
  try {
    const j = JSON.parse(fs.readFileSync(pj, 'utf8'));
    versions[d.name] = j.version;
    if (fs.existsSync(path.join(SCAN, d.name, 'lib', 'client.js'))) pluginIds.push(d.name);
  } catch { /* 跳过坏 package.json */ }
}

// ---- 2. dsh 本体元信息 -----------------------------------------------------
let dsh = null;
try {
  // dsh 本体在**外层**根里（嵌套布局下 SCAN 是 dsh/node_modules/@deepseek-ai，不含 dsh 自己），
  // 所以两处都找一遍，否则会得到 dsh=undefined。
  const cand = [path.join(SCAN, 'dsh', 'package.json'), path.join(ROOT, 'dsh', 'package.json')];
  const found = cand.find((f) => fs.existsSync(f));
  if (found === undefined) throw new Error('no dsh package.json');
  const j = JSON.parse(fs.readFileSync(found, 'utf8'));
  dsh = {
    version: j.version,
    engines: j.engines ?? null,
    deps: Object.keys(j.dependencies ?? {}).length,
    optionalDeps: Object.keys(j.optionalDependencies ?? {}).length,
  };
} catch { /* ignore */ }

// ---- 3. 客户端方法表（协议层破坏性变更的判据）------------------------------
const remotes = read(path.join(SCAN, 'dsh-api-remotes', 'lib', 'client.js'));
// 注意：descriptor 的 id 前缀**不都是** dsh-api-*（还有 dsh-plugin-manager /
// dsh-cordis-host-runner / dsh-goal / dsh-llm / dsh-message-feedback 等），
// 只按 dsh-api-* 抽会漏掉一整类 —— 那样破坏性变更会被隐藏。这里抽全部 id。
const methods = uniq((remotes?.match(/\bid: "([^"#]+#[^"]+)"/g) ?? [])
  .map((s) => s.slice(s.indexOf('"') + 1, -1)));

// ---- 4. 设置页 CSS Module 前缀 + 三条特征串 --------------------------------
const settings = read(path.join(SCAN, 'dsh-client-ui-settings-general', 'lib', 'client.js'));
const cssPrefixes = uniq((settings?.match(/[A-Za-z0-9_-]{5,8}_[a-zA-Z]+/g) ?? [])
  .filter((s) => /^[A-Za-z0-9]{5,6}_/.test(s)));
const cssFeatures = {
  navWidth188: (settings?.includes('width:188px') ?? false),
  panelWidth800: (settings?.includes('width:800px') ?? false),
  rowSpaceBetween: /_row\{[^}]*justify-content:space-between/.test(settings ?? ''),
};

// ---- 5. boot 内核标记（BOOT_SETTLE 的挂点）---------------------------------
const distDir = path.join(SCAN, 'dsh-web-frontend', 'dist', 'assets');
let shell = null;
try {
  const f = fs.readdirSync(distDir).filter((n) => /^index-.*\.js$/.test(n)).sort()
    .map((n) => ({ n, s: fs.statSync(path.join(distDir, n)).size })).sort((a, b) => b.s - a.s)[0];
  if (f) shell = read(path.join(distDir, f.n));
} catch { /* ignore */ }
const bootKernel = {
  shellEntry: shell ? 'found' : 'missing',
  didNotActivate: shell?.includes('did not activate') ?? false,
  entriesStart: /entries\.start\(/.test(shell ?? ''),
  stateMap: /PENDING:0,LOADING:1,ACTIVE:2/.test(shell ?? ''),
  waitingForService: shell?.includes('waiting for service') ?? false,
  // 取“did not activate”附近那段，作为人工复核用的原文
  excerpt: (() => {
    const i = shell?.indexOf('did not activate') ?? -1;
    return i < 0 ? null : shell.slice(Math.max(0, i - 420), i + 120);
  })(),
};

// ---- 6. resources 的 protocolOf（URL authority 垫片的服务对象）-------------
const resources = read(path.join(SCAN, 'dsh-client-resources', 'lib', 'client.js'));
const protocolOfExcerpt = (() => {
  const i = resources?.indexOf('function protocolOf') ?? -1;
  return i < 0 ? null : resources.slice(i, i + 340);
})();

// ---- 7. 带原生二进制的包（arm64 安装面；有界遍历，避免把时间耗在巨型依赖树上）
const nativePkgs = {};
function findNative(dir, depth, acc, limit = 4) {
  if (depth > limit || acc.length > 6) return;
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
  for (const e of entries) {
    if (e.name === 'node_modules' || e.name === '.git') continue;
    const p = path.join(dir, e.name);
    if (e.isFile() && e.name.endsWith('.node')) { acc.push(path.relative(SCAN, p)); continue; }
    if (e.isDirectory()) findNative(p, depth + 1, acc);
  }
}
for (const name of Object.keys(versions)) {
  const acc = [];
  findNative(path.join(SCAN, name), 0, acc);
  if (acc.length) nativePkgs[name] = acc.slice(0, 6);
}

const out = {
  root: RESOLVED.root,
  givenRoot: ROOT,
  layout: RESOLVED.layout,
  generatedAt: new Date().toISOString(),
  dsh,
  packageCount: Object.keys(versions).length,
  pluginIds: uniq(pluginIds),
  versions,
  methods,
  methodCount: methods.length,
  methodNamespaces: uniq(methods.map((m) => m.split('#')[1].split('/')[0])),
  cssPrefixes,
  cssFeatures,
  bootKernel,
  protocolOfExcerpt,
  nativePkgs,
};
fs.writeFileSync(OUT, JSON.stringify(out, null, 2));
console.log(`快照已写出: ${OUT}`);
console.log(`  扫描根=${RESOLVED.root}\n  布局=${RESOLVED.layout}${RESOLVED.root !== ROOT ? `（已从 ${ROOT} 自动下探）` : ''}`);
console.log(`  dsh=${dsh?.version} 包数=${out.packageCount} 客户端插件=${out.pluginIds.length}`);
console.log(`  方法表=${out.methods.length}（${out.methodNamespaces.length} 个命名空间） 设置页前缀=${out.cssPrefixes.length} 原生包=${Object.keys(out.nativePkgs).length}`);
