// 对比两份快照，只报「升级会踩到的东西」。用法: node diff.mjs a.json b.json
import fs from 'node:fs';
const [a, b] = process.argv.slice(2).map((p) => JSON.parse(fs.readFileSync(p, 'utf8')));
const setDiff = (x, y) => ({ added: y.filter((v) => !x.includes(v)), removed: x.filter((v) => !y.includes(v)) });
const S = (t) => `\n${'='.repeat(72)}\n${t}\n${'='.repeat(72)}`;

console.log(S(`dsh ${a.dsh?.version}  →  ${b.dsh?.version}`));
console.log(`  包数 ${a.packageCount} → ${b.packageCount}   客户端插件 ${a.pluginIds.length} → ${b.pluginIds.length}`);
console.log(`  engines: ${JSON.stringify(a.dsh?.engines)} → ${JSON.stringify(b.dsh?.engines)}`);

const m = setDiff(a.methods, b.methods);
console.log(S(`★ 客户端方法表（协议层破坏性变更的判据）  ${a.methods.length} → ${b.methods.length}`));
console.log(`  新增 ${m.added.length} 个:`); m.added.forEach((x) => console.log('    + ' + x));
console.log(`  删除 ${m.removed.length} 个:`); m.removed.forEach((x) => console.log('    - ' + x));
if (!m.added.length && !m.removed.length) console.log('  （无变化）');
const nsA = a.methodNamespaces ?? [], nsB = b.methodNamespaces ?? [];
const ns = setDiff(nsA, nsB);
if (ns.added.length || ns.removed.length) {
  console.log(`  命名空间: +[${ns.added}]  -[${ns.removed}]`);
}

console.log(S('★ 设置页 CSS 覆盖层（窄屏 左右→上下）'));
console.log(`  特征串 width:188px=${a.cssFeatures.navWidth188}→${b.cssFeatures.navWidth188}`
  + `  width:800px=${a.cssFeatures.panelWidth800}→${b.cssFeatures.panelWidth800}`
  + `  row space-between=${a.cssFeatures.rowSpaceBetween}→${b.cssFeatures.rowSpaceBetween}`);
const cp = setDiff(a.cssPrefixes, b.cssPrefixes);
console.log(`  前缀 ${a.cssPrefixes.length} → ${b.cssPrefixes.length}；新增 ${cp.added.length} 删除 ${cp.removed.length}`);
if (cp.removed.length) console.log('    删除的前缀（覆盖层若用到就要改）: ' + cp.removed.join(' '));

console.log(S('★ boot 内核标记（BOOT_SETTLE 的挂点）'));
for (const k of ['shellEntry', 'didNotActivate', 'entriesStart', 'stateMap', 'waitingForService']) {
  console.log(`  ${k.padEnd(20)} ${String(a.bootKernel[k]).padEnd(10)} → ${b.bootKernel[k]}`);
}

console.log(S('★ protocolOf（URL authority 垫片的服务对象）'));
console.log('  --- 旧 ---\n' + (a.protocolOfExcerpt ?? '(未找到)').split('\n').slice(0, 12).join('\n'));
console.log('  --- 新 ---\n' + (b.protocolOfExcerpt ?? '(未找到)').split('\n').slice(0, 12).join('\n'));

console.log(S('★ 客户端插件清单变化'));
const pl = setDiff(a.pluginIds, b.pluginIds);
console.log(`  新增 ${pl.added.length}: ${pl.added.join(' ') || '（无）'}`);
console.log(`  移除 ${pl.removed.length}: ${pl.removed.join(' ') || '（无）'}`);

console.log(S('★ @deepseek-ai 包版本变化'));
const names = [...new Set([...Object.keys(a.versions), ...Object.keys(b.versions)])].sort();
let n = 0;
for (const k of names) {
  if (a.versions[k] !== b.versions[k]) { console.log(`  ${k.padEnd(52)} ${a.versions[k] ?? '(无)'} → ${b.versions[k] ?? '(移除)'}`); n++; }
}
console.log(`  共 ${n} 个包版本变化`);
