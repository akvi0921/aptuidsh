// =============================================================================
// APTUIDSH · loader.await 收敛补丁的单测
// -----------------------------------------------------------------------------
// 1.3.7 给 dsh 的 loader.await() 加了「等到插件名单收敛」：这是本仓库唯一一处
// **有意改变 dsh 行为**的补丁（真机 boot 竞态的修复），所以它必须有真实计时验证：
//   · 已收敛 → 立刻返回，不给正常启动添延迟
//   · 竞态中 → 等到 pending 归零
//   · 永久卡住 → 约 3 秒放手，**绝不挂死启动**
// 从 WebPolyfill.kt 抽真实源码，在 Node 里配 window/document 假体执行，
// 用真实时钟观察 settle() 的行为（不 mock 计时器，避免测出「假绿」）。
// =============================================================================
const PENDING=0, LOADING=1, ACTIVE=2, FAILED=3;
globalThis.window = { addEventListener(){}, __aptuidshErrors: [] };
globalThis.document = { readyState: 'loading', querySelector(){ return null; }, querySelectorAll(){ return []; }, body: { innerHTML: '' } };
const src = process.env.PROBE_SRC;
new Function(src)();
const st = window.__aptuidshProbe;
if (!st || !st.diag || typeof st.diag.settle !== 'function') { console.log('FAIL: 探针未暴露 diag'); process.exit(1); }

function fakeLoader(states) {
  return {
    ctx: { get(){ return undefined; } },
    entries(){ return states.map((s) => ({ fiber: { state: s, inject: {} } })); },
  };
}
let ok = 0, bad = 0;
const t = (name, cond) => { cond ? (ok++, console.log('  ok   ' + name)) : (bad++, console.log('  FAIL ' + name)); };

st.resCtx = { get: (n) => (n === 'loader' ? fakeLoader([ACTIVE, ACTIVE]) : undefined) };
t('counts() 认得全 active', st.diag.counts().pending === 0 && st.diag.counts().active === 2);
let t0 = Date.now();
await st.diag.settle();
t('已收敛时 settle 立刻返回(<300ms)', Date.now() - t0 < 300);

const states = [PENDING, PENDING, ACTIVE];
st.resCtx = { get: (n) => (n === 'loader' ? fakeLoader(states) : undefined) };
setTimeout(() => { states[0] = ACTIVE; }, 200);
setTimeout(() => { states[1] = ACTIVE; }, 400);
t0 = Date.now();
await st.diag.settle();
const waited = Date.now() - t0;
t('竞态场景等到收敛(>=400ms) 且不超时(<2000ms)  [实测 ' + waited + 'ms]', waited >= 400 && waited < 2000);

st.resCtx = { get: (n) => (n === 'loader' ? fakeLoader([PENDING, PENDING]) : undefined) };
t0 = Date.now();
await st.diag.settle();
const stuck = Date.now() - t0;
t('卡死时约 3 秒放手(2500~6000ms)  [实测 ' + stuck + 'ms]', stuck >= 2500 && stuck < 6000);
t('放手后记下 roster-stuck', st.target.some((x) => String(x).indexOf('roster-stuck') === 0));

st.resCtx = { get: () => undefined };
t('拿不到 loader 时 counts() 返回 null 而不抛', st.diag.counts() === null);

console.log('  --- settle 单测：通过 ' + ok + ' / 失败 ' + bad);
process.exit(bad === 0 ? 0 : 1);
