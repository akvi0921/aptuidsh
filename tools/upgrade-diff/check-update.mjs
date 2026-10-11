#!/usr/bin/env node
// =============================================================================
// APTUIDSH · 检查内置 dsh 是否有官方更新
// -----------------------------------------------------------------------------
// 一条命令回答「要不要升级内置 dsh」，并顺手交叉核对三条渠道，避免单点误判：
//   1. npm registry.npmjs.org      —— **权威**：我们安装的来源就是它
//   2. npm registry.npmmirror.com  —— 国内镜像，用来发现镜像滞后
//   3. GitHub 的 tag / Release      —— 源码侧信号，能发现「已发版但还没上 npm」
//
// 用法:
//   node tools/upgrade-diff/check-update.mjs              # 人类可读
//   node tools/upgrade-diff/check-update.mjs --json       # 机器可读
//   node tools/upgrade-diff/check-update.mjs --channel latest|next|alpha
//
// 「我们跟哪条通道」默认 next（本项目 0.1.7-rc.1 与 0.2.0-rc.2 都是从 next 装的）。
// alpha 是**下一版的早期预览**，比 rc 更不稳，默认只报告不推荐。
//
// 退出码:
//   0  已是最新（无需动作）
//   1  有可升级版本（跟踪通道上出现了比我们新的版本）
//   2  至少一个渠道查询失败（结论不完整，别据此决策）
// =============================================================================
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..', '..');
const PKG = '@deepseek-ai/dsh';

const argv = process.argv.slice(2);
const asJson = argv.includes('--json');
const ci = argv.indexOf('--channel');
const CHANNEL = (ci >= 0 ? argv[ci + 1] : process.env.DSH_CHANNEL) || 'next';

// ---------------------------------------------------------------- 版本号比较
const PRE_RANK = { alpha: 0, beta: 1, rc: 2 };
/** 把 semver 解析成可比较的元组；不合法返回 null。 */
function parseVer(v) {
  const m = /^(\d+)\.(\d+)\.(\d+)(?:-([A-Za-z]+)\.?(\d+)?)?/.exec(String(v).trim());
  if (!m) return null;
  const pre = m[4] === undefined ? null : String(m[4]).toLowerCase();
  return {
    raw: String(v).trim(),
    nums: [Number(m[1]), Number(m[2]), Number(m[3])],
    pre,
    preNum: m[5] === undefined ? 0 : Number(m[5]),
  };
}
/** a > b 返回正数，相等返回 0，a < b 返回负数；无法解析时按「不可比」处理。 */
function cmpVer(a, b) {
  const A = parseVer(a), B = parseVer(b);
  if (!A || !B) return 0;
  for (let i = 0; i < 3; i += 1) if (A.nums[i] !== B.nums[i]) return A.nums[i] - B.nums[i];
  // 同为正式版；或一方是预发布：预发布 < 正式
  const ra = A.pre === null ? 3 : PRE_RANK[A.pre] ?? -1;
  const rb = B.pre === null ? 3 : PRE_RANK[B.pre] ?? -1;
  if (ra !== rb) return ra - rb;
  return A.preNum - B.preNum;
}

// ---------------------------------------------------------------- 取我们的版本
/** 我们 **APK 内置**的 dsh 版本（构建时由 tools/build-rootfs.sh 写入）。 */
function bundledVersion() {
  const f = path.join(REPO, 'app/src/main/assets/image-version.txt');
  try { return fs.readFileSync(f, 'utf8').trim() || null; } catch { return null; }
}
/** 构建工作目录里**实际安装**的版本（可与内置不同；OTA 场景会不同）。 */
function installedVersion() {
  const f = path.join(process.env.HOME || '', 'aptuidsh-rootfs/rootfs/usr/local/lib/node_modules',
    '@deepseek-ai', 'dsh', 'package.json');
  try { return JSON.parse(fs.readFileSync(f, 'utf8')).version || null; } catch { return null; }
}

// ---------------------------------------------------------------- 网络
async function getJson(url, ms = 20000) {
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), ms);
  try {
    const r = await fetch(url, { signal: ac.signal, headers: { 'user-agent': 'aptuidsh-check-update', accept: 'application/json' } });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return await r.json();
  } finally { clearTimeout(t); }
}
const fmtTime = (iso) => {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '?';
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
};
const daysAgo = (iso) => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : Math.floor((Date.now() - d.getTime()) / 86400000);
};

async function fromNpm(registry) {
  const d = await getJson(`${registry}/${PKG.replace('/', '%2F')}`);
  const tags = d['dist-tags'] || {};
  const time = d.time || {};
  const all = Object.keys(d.versions || {});
  return {
    registry,
    tags,
    count: all.length,
    versions: all.sort((a, b) => cmpVer(a, b)),
    latestByTime: all
      .filter((v) => time[v])
      .sort((a, b) => new Date(time[b]) - new Date(time[a]))
      .slice(0, 5)
      .map((v) => ({ version: v, at: time[v] })),
  };
}

async function fromGitHub() {
  const repo = 'deepseek-ai/deepseek-harness';
  const rel = await getJson(`https://api.github.com/repos/${repo}/releases?per_page=4`);
  return {
    repo,
    releases: (rel || []).map((r) => ({ tag: r.tag_name, at: r.published_at, prerelease: !!r.prerelease })),
    // tag 形如 dsh-v0.2.1-alpha.2
    newest: (rel || []).map((r) => String(r.tag_name).replace(/^dsh-v/, '')).sort((a, b) => cmpVer(a, b)).pop() || null,
  };
}

// ---------------------------------------------------------------- 主流程
const problems = [];
const bundled = bundledVersion();
const installed = installedVersion();

let npm, mirror, gh;
await Promise.all([
  fromNpm('https://registry.npmjs.org').then((x) => { npm = x; }, (e) => problems.push(`npm registry 查询失败：${e.message}`)),
  fromNpm('https://registry.npmmirror.com').then((x) => { mirror = x; }, (e) => problems.push(`npmmirror 查询失败：${e.message}`)),
  fromGitHub().then((x) => { gh = x; }, (e) => problems.push(`GitHub 查询失败：${e.message}`)),
]);

const ours = bundled || installed;
const tracked = npm?.tags?.[CHANNEL] || null;
const newerThanOurs = (npm?.versions || []).filter((v) => ours && cmpVer(v, ours) > 0);
const newerThanTracked = (npm?.versions || []).filter((v) => tracked && cmpVer(v, tracked) > 0);
const upgradeable = !!(tracked && ours && cmpVer(tracked, ours) > 0);
// 注意：**不能**用 JSON.stringify 比 dist-tags —— 键顺序不同就会误报「镜像滞后」。
// 要逐 tag 比。同理，「GitHub 领先」的判据不该拿 npm 的 latest 比（alpha 线本来就在 latest 前面，
// 那是正常状态），而应看 GitHub 上是否存在 npm **完全还没有**的版本。
const mirrorLag = !!(npm && mirror && ['latest', 'next', 'alpha'].some((k) => (npm.tags?.[k] || null) !== (mirror.tags?.[k] || null)));
const npmNewest = (npm?.versions || []).slice(-1)[0] || null;
const ghNotOnNpm = !!(gh?.newest && npmNewest && cmpVer(gh.newest, npmNewest) > 0) ? gh.newest : null;

const verdict = problems.length
  ? '结论不完整：有渠道查询失败，请勿据此决策'
  : upgradeable
    ? `有更新：跟踪通道 ${CHANNEL} 上是 ${tracked}，我们内置 ${ours}`
    : `已是最新：跟踪通道 ${CHANNEL} 上就是 ${tracked || '?'}，与内置${ours ? ` ${ours}` : '版本'}一致`;

const result = {
  checkedAt: new Date().toISOString(),
  channel: CHANNEL,
  ours: { bundled, installed },
  upgradeable,
  verdict,
  npm: npm ? { tags: npm.tags, count: npm.count, recent: npm.latestByTime } : null,
  mirror: mirror ? { tags: mirror.tags, lag: mirrorLag } : null,
  github: gh || null,
  newerThanOurs,
  newerThanTracked,
  warnings: [
    mirrorLag ? '国内镜像的 dist-tags 与 npm 官方不一致（镜像滞后）' : null,
    ghNotOnNpm ? `GitHub 上存在 npm 尚未发布的版本：${ghNotOnNpm}` : null,
  ].filter(Boolean),
  problems,
};

if (asJson) {
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
} else {
  const line = (s = '') => process.stdout.write(`${s}\n`);
  line('='.repeat(74));
  line(`内置 dsh 更新检查   ${fmtTime(result.checkedAt)}   （跟踪通道：${CHANNEL}）`);
  line('='.repeat(74));
  line();
  line(`  我们内置（APK assets/image-version.txt） : ${bundled || '（读不到）'}`);
  if (installed && installed !== bundled) line(`  构建目录里实际安装的                     : ${installed}${bundled ? '  ← 与内置不同' : ''}`);
  line();
  line('  ── 官方通道 ────────────────────────────────────────────────────────');
  if (npm) {
    for (const t of ['latest', 'next', 'alpha']) {
      const v = npm.tags[t];
      if (!v) continue;
      const mark = t === CHANNEL ? '← 我们跟踪' : '';
      const same = ours && v === ours ? '✓ 与内置一致' : '';
      line(`  npm ${t.padEnd(6)} : ${String(v).padEnd(16)}${mark.padEnd(12)}${same}`);
    }
    line(`  npm 版本总数 : ${npm.count}`);
  } else line('  npm          : （查询失败）');
  if (mirror) line(`  镜像 npmmirror: ${JSON.stringify(mirror.tags)}${mirrorLag ? '   ⚠ 与官方不一致' : '   ✓ 与官方一致'}`);
  if (gh) {
    line(`  GitHub 最新   : ${gh.newest || '?'}${ghNotOnNpm ? '   ⚠ npm 上还没有' : ''}`);
    for (const r of gh.releases.slice(0, 3)) {
      line(`      ${r.tag.padEnd(24)} ${fmtTime(r.at)}  ${r.prerelease ? 'prerelease' : 'release'}`);
    }
  }
  line();
  line('  ── 官方最近发布 ────────────────────────────────────────────────────');
  for (const r of (npm?.latestByTime || [])) {
    const d = daysAgo(r.at);
    line(`  ${r.version.padEnd(18)} ${fmtTime(r.at)}${d === null ? '' : `  （${d} 天前）`}`);
  }
  line();
  line('  ── 结论 ────────────────────────────────────────────────────────────');
  line(`  ${upgradeable ? '⬆' : '✓'} ${verdict}`);
  if (newerThanOurs.length) {
    line(`  比我们内置更新的版本（共 ${newerThanOurs.length} 个）：`);
    for (const v of newerThanOurs.slice(-6)) {
      const tag = Object.entries(npm?.tags || {}).filter(([, x]) => x === v).map(([k]) => k).join('/');
      line(`      ${v.padEnd(18)}${tag ? `（${tag} 通道）` : '（已被更新的版本取代）'}`);
    }
    if (newerThanOurs.every((v) => parseVer(v)?.pre === 'alpha')) {
      line('      ↳ 全部是 alpha 预发布版：比 rc 更早、更不稳，一般**不建议**跟。');
    }
  }
  for (const w of result.warnings) line(`  ⚠ ${w}`);
  for (const p of problems) line(`  ✗ ${p}`);
  line();
  if (upgradeable) {
    line('  下一步：');
    line('    1. 把新版本装到 scratch 路径（勿覆盖现有安装）');
    line('    2. node tools/upgrade-diff/snapshot.mjs <新旧两处的 @deepseek-ai> 各取一份');
    line('    3. node tools/upgrade-diff/diff.mjs 旧.json 新.json   → 看破坏面');
    line('    4. 跑垫片体检 audit_browser_api.py（见 tools/upgrade-diff/README.md）');
    line('    5. bash tools/build-rootfs.sh ~/aptuidsh-rootfs <新版本>  → 重建镜像');
  }
  line();
}

process.exit(problems.length ? 2 : upgradeable ? 1 : 0);
