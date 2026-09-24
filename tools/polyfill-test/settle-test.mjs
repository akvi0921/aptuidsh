// =============================================================================
// APTUIDSH · boot 收敛补丁（BOOT_SETTLE）的单测
// -----------------------------------------------------------------------------
// 这是本仓库唯一一处**有意改变 dsh 行为**的补丁 —— 用于修 dsh web boot 的竞态：
// 内核在「模块加载完」之后立刻一次性要求所有插件都是 active，而 remote.* 命名空间
// 要等 22 次串行挂载才出现，旧内核上必然撞窗口，页面永久停在 Failed to load plugins。
//
// 正因为它改变了行为，必须有**真实计时**验证：
//   · 已收敛        → 立刻返回，不给正常启动添延迟
//   · 竞态中        → 等到未安定数归零
//   · 永久卡住      → 约 3 秒放手，**绝不挂死启动**
//
// 从 WebPolyfill.kt 抽真实源码，在 Node 里配 window/document 假体执行；
// 用真实时钟观察 settle()（不 mock 计时器，避免测出「假绿」）。
// =============================================================================
const PENDING = 0, LOADING = 1, ACTIVE = 2, FAILED = 3;

globalThis.window = { addEventListener() {} };
globalThis.document = { readyState: 'loading' };

import { extract } from './extract.mjs';
new Function(extract('BOOT_SETTLE'))();

const api = window.__aptuidshBootSettle;
if (!api || typeof api.settle !== 'function' || typeof api.counts !== 'function') {
  console.log('FAIL: 补丁未暴露 __aptuidshBootSettle 把手');
  process.exit(1);
}
const fakeLoader = (states) => ({
  entries: () => states.map((s) => ({ fiber: s === undefined ? undefined : { state: s } })),
});

let ok = 0, bad = 0;
const t = (name, cond) => { cond ? (ok += 1, console.log('  ok   ' + name)) : (bad += 1, console.log('  FAIL ' + name)); };

// --- 计数语义 ---------------------------------------------------------------
t('ACTIVE / FAILED 都不算「未安定」', api.counts(fakeLoader([ACTIVE, FAILED])) === 0);
t('PENDING / LOADING / 无 fiber 都算「未安定」',
  api.counts(fakeLoader([PENDING, LOADING, undefined, ACTIVE])) === 3);
t('拿不到 loader 时返回 -1 而不抛', api.counts(null) === -1 && api.counts({}) === -1);
t('entries() 抛异常时返回 -1 而不抛', api.counts({ entries() { throw new Error('boom'); } }) === -1);

// --- 计时行为 ---------------------------------------------------------------
let t0 = Date.now();
await api.settle(fakeLoader([ACTIVE, ACTIVE]));
t('已收敛时立刻返回(<300ms)', Date.now() - t0 < 300);

const states = [PENDING, PENDING, ACTIVE];
setTimeout(() => { states[0] = ACTIVE; }, 200);
setTimeout(() => { states[1] = ACTIVE; }, 400);
t0 = Date.now();
await api.settle(fakeLoader(states));
const waited = Date.now() - t0;
t('竞态中等到收敛(>=400ms) 且不超时(<2000ms)  [实测 ' + waited + 'ms]', waited >= 400 && waited < 2000);

t0 = Date.now();
await api.settle(fakeLoader([PENDING, PENDING]));
const stuck = Date.now() - t0;
t('永久卡住时约 3 秒放手(2500~6000ms)  [实测 ' + stuck + 'ms]', stuck >= 2500 && stuck < 6000);

t0 = Date.now();
await api.settle(null);
t('拿不到 loader 时立刻返回，不空等', Date.now() - t0 < 300);

t('未接管到真实 loader 前 patched() 为 false', api.patched() === false);

console.log('  --- boot 收敛补丁单测：通过 ' + ok + ' / 失败 ' + bad);
process.exit(bad === 0 ? 0 : 1);
