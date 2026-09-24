#!/usr/bin/env node
/**
 * APTUIDSH · WebView 兼容垫片自测
 * ---------------------------------------------------------------------------
 * 目标内核是 **Chrome 114**（本机机型实测的系统 WebView），而官方 Web UI 按现代
 * 浏览器构建。本脚本做三件事：
 *
 *  1. 从 `WebPolyfill.kt` 里**抽出真实垫片源码**（单一真源，绝不手抄一份到测试里，
 *     否则测试和产品会漂移）；
 *  2. 在 Node 里**模拟 Chrome 114**：删掉所有「Chrome > 114 才加入」的 API；
 *  3. 跑垫片，逐条断言「补上了 + 语义正确 + 幂等 + 不覆盖原生」。
 *
 * 只对 `document` 缺失做自然处理（worker 注入守卫会自行跳过），其余全用 Node 自带实现。
 *
 * 用法: node tools/polyfill-test/check.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PROJ = path.resolve(HERE, '../..');
const KT = path.join(PROJ, 'app/src/main/java/com/aptuidsh/kui/WebPolyfill.kt');

let pass = 0, fail = 0;
const failures = [];
function ok(name, cond, extra) {
  if (cond) { pass++; }
  else { fail++; failures.push(name + (extra ? '  → ' + extra : '')); }
}
function eq(name, actual, expected) {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  ok(name, a === e, `实际 ${a}，期望 ${e}`);
}

// ------------------------------------------------------------------ 抽源码
const kt = fs.readFileSync(KT, 'utf8');
function extract(kotlinName) {
  const marker = `private const val ${kotlinName} = """`;
  const i = kt.indexOf(marker);
  if (i < 0) throw new Error(`WebPolyfill.kt 里找不到 ${kotlinName}`);
  const start = i + marker.length;
  const end = kt.indexOf('\n"""', start);
  if (end < 0) throw new Error(`${kotlinName} 的结束定界符缺失`);
  return kt.slice(start, end + 1);
}
const JS = extract('JS');
const GUARD = extract('GUARD');

console.log(`抽出垫片源码 ${JS.length} 字符 / 守卫 ${GUARD.length} 字符`);
// Kotlin 原样字符串里出现美元符号会变成模板起始符、直接编译不过；这里提前守一道
ok('垫片源码里没有美元符号（Kotlin 原样字符串的模板起始符）',
  !JS.includes('$') && !GUARD.includes('$'),
  `JS 有 ${(JS.match(/\$/g) || []).length} 个，GUARD 有 ${(GUARD.match(/\$/g) || []).length} 个`);
ok('垫片源码非空且看起来是 JS', JS.includes('Math.sumPrecise') && JS.includes('Iterator'));

// -------------------------------------------- 先抓原生实现的行为做基准
// 本机 Node 26 已自带这批「新 API」，因此可以做**差分对拍**：
// 先记下原生结果，删掉 API、装垫片，再逐条比对。这比手写期望值可靠得多
// （实战教训：RegExp.escape 的转义规则我一开始凭记忆写错了，就是对拍抓出来的）。
const CORPUS = (() => {
  const out = [];
  for (let c = 0; c < 256; c++) {
    const ch = String.fromCharCode(c);
    out.push(ch, 'Z' + ch + 'Y', 'a' + ch, ch + 'a');
  }
  out.push(
    '', 'a.b', '1a', 'abc', '(*.*)', 'Buy it. use it. break it. fix it.',
    '${}', 'a-b', '_x', 'price: 3.5 (USD) [x]', 'a\\b', 'a/b', '^$', '|?',
    '你好世界', 'a中b', '😀x', 'a\tb', 'a\nb', 'NBSP\u00a0here',
    'Chapter 1.2.3', 'Fig. 4(a)', 'x^2 + y^2 = z^2',
  );
  // 伪随机 ASCII，覆盖组合情况
  let seed = 12345;
  const rnd = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
  for (let i = 0; i < 300; i++) {
    let s = '';
    const len = 1 + Math.floor(rnd() * 12);
    for (let j = 0; j < len; j++) s += String.fromCharCode(32 + Math.floor(rnd() * 95));
    out.push(s);
  }
  return out;
})();

const BYTES_CORPUS = (() => {
  const out = [];
  for (let n = 0; n <= 40; n++) {
    const a = new Uint8Array(n);
    for (let i = 0; i < n; i++) a[i] = (i * 37 + n * 11) & 255;
    out.push(a);
  }
  let seed = 999;
  const rnd = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
  for (let k = 0; k < 120; k++) {
    const a = new Uint8Array(1 + Math.floor(rnd() * 64));
    for (let i = 0; i < a.length; i++) a[i] = Math.floor(rnd() * 256);
    out.push(a);
  }
  return out;
})();

const NUM_CORPUS = [
  [], [1], [1, 2, 3], [0.1, 0.2], [1e20, 1, -1e20], [1e-20, 1, -1],
  Array.from({ length: 100 }, (_, i) => (i % 7) * 0.1), [Number.MAX_VALUE, Number.MAX_VALUE, -Number.MAX_VALUE],
];

const nativeRef = {};
nativeRef.hasEscape = typeof RegExp.escape === 'function';
nativeRef.hasBase64 = typeof Uint8Array.fromBase64 === 'function'
  && typeof Uint8Array.prototype.toBase64 === 'function';
nativeRef.hasHex = typeof Uint8Array.fromHex === 'function'
  && typeof Uint8Array.prototype.toHex === 'function';
nativeRef.hasSum = typeof Math.sumPrecise === 'function';
nativeRef.hasUrlParse = typeof URL.parse === 'function';

if (nativeRef.hasEscape) nativeRef.escape = CORPUS.map((s) => RegExp.escape(s));
if (nativeRef.hasBase64) nativeRef.toBase64 = BYTES_CORPUS.map((b) => b.toBase64());
if (nativeRef.hasBase64) nativeRef.fromBase64 = nativeRef.toBase64.map((s) => Array.from(Uint8Array.fromBase64(s)));
if (nativeRef.hasHex) nativeRef.toHex = BYTES_CORPUS.map((b) => b.toHex());
if (nativeRef.hasSum) nativeRef.sum = NUM_CORPUS.map((xs) => Math.sumPrecise(xs));
if (nativeRef.hasUrlParse) nativeRef.urlParse = CORPUS.map((s) => {
  const u = URL.parse(s, 'https://example.com/base/');
  return u === null ? null : u.href;
});
console.log(`原生基准：escape=${nativeRef.hasEscape} base64=${nativeRef.hasBase64} hex=${nativeRef.hasHex} sumPrecise=${nativeRef.hasSum} URL.parse=${nativeRef.hasUrlParse}`);

// ------------------------------------------------------ 模拟 Chrome 114
const NEW_APIS = [
  ['Iterator', null],
  ['Promise.withResolvers', Promise],
  ['Promise.try', Promise],
  ['URL.parse', URL],
  ['RegExp.escape', RegExp],
  ['Math.sumPrecise', Math],
  ['Object.groupBy', Object],
  ['Map.groupBy', Map],
  ['Array.fromAsync', Array],
  ['Uint8Array.fromBase64', Uint8Array],
  ['Uint8Array.fromHex', Uint8Array],
  ['Uint8Array.prototype.toBase64', Uint8Array.prototype],
  ['Uint8Array.prototype.toHex', Uint8Array.prototype],
  ['Uint8Array.prototype.setFromBase64', Uint8Array.prototype],
  ['Uint8Array.prototype.setFromHex', Uint8Array.prototype],
  ['Blob.prototype.bytes', Blob.prototype],
  ['Response.prototype.bytes', Response.prototype],
  ['Request.prototype.bytes', Request.prototype],
  ['AbortSignal.any', AbortSignal],
  ['AbortSignal.timeout', AbortSignal],
  ['Symbol.dispose', Symbol],
  ['Symbol.asyncDispose', Symbol],
  ['Set.prototype.union', Set.prototype],
  ['Set.prototype.intersection', Set.prototype],
  ['Set.prototype.difference', Set.prototype],
  ['Set.prototype.symmetricDifference', Set.prototype],
  ['Set.prototype.isSubsetOf', Set.prototype],
  ['Set.prototype.isSupersetOf', Set.prototype],
  ['Set.prototype.isDisjointFrom', Set.prototype],
];

let deleted = 0, blocked = 0;
for (const [name, holder] of NEW_APIS) {
  const key = name.includes('.') ? name.split('.').pop() : name;
  const target = holder === null ? globalThis : holder;
  try {
    if (delete target[key]) deleted++;
    else if (target[key] === undefined) deleted++;
    else { blocked++; }
  } catch { blocked++; }
}
console.log(`模拟 Chrome 114：已删除 ${deleted} 个 API${blocked ? `，${blocked} 个删不掉` : ''}`);

// 前置断言：确实缺了（否则后面的「补上了」是假绿）
ok('前置：Iterator 确实不存在', typeof globalThis.Iterator === 'undefined');
ok('前置：Promise.withResolvers 确实不存在', typeof Promise.withResolvers === 'undefined');
ok('前置：Uint8Array.fromBase64 确实不存在', typeof Uint8Array.fromBase64 === 'undefined');
ok('前置：RegExp.escape 确实不存在', typeof RegExp.escape === 'undefined');

// ------------------------------------------------------------ 跑垫片
(0, eval)(JS);

// ---------------------------------------------------- D. 窄屏布局覆盖层
console.log('\n---------- D. 窄屏布局覆盖层（官方设置页 左右→上下）----------');
{
  const CSS = extract('CSS');
  const LAYOUT_CHECK = extract('LAYOUT_CHECK');
  console.log(`抽出覆盖样式 ${CSS.length} 字符 / 自检脚本 ${LAYOUT_CHECK.length} 字符`);

  ok('CSS 里没有美元符号（Kotlin 原样字符串模板起始符）', !CSS.includes('$'));
  ok('LAYOUT_CHECK 里没有美元符号', !LAYOUT_CHECK.includes('$'));

  // 花括号配平（CSS 语法最低要求）
  const open = (CSS.match(/\{/g) || []).length, close = (CSS.match(/\}/g) || []).length;
  eq('CSS 花括号配平', open, close);

  // 这几条是「设置页左右→上下」的关键，缺一不可
  ok('覆盖了设置弹窗面板（flex-direction: column）',
    /\[class\*="VOzbGW_panel"\][^}]*flex-direction:\s*column\s*!important/.test(CSS));
  ok('覆盖了左侧竖排导航（改为横向排列）',
    /\[class\*="VOzbGW_nav"\][^}]*flex-direction:\s*row\s*!important/.test(CSS));
  ok('覆盖了导航列表（横向 + 自身滚动）',
    /\[class\*="VOzbGW_navList"\][^}]*flex-direction:\s*row\s*!important/.test(CSS));
  ok('覆盖了设置行（窄屏下标题在上、控件在下）',
    /\[class\*="Pt1bsG_row"\][^}]*flex-direction:\s*column\s*!important/.test(CSS));

  // 必须全是 !important：dsh 的插件样式是运行时 append 到 <head> 末尾的，
  // 同为单类选择器时它排在我们后面、不加 !important 覆盖不住（这条是踩坑结论）
  // 注意用 (?<![\w-]) 前缀：否则 `max-width:` 会被 `width` 误命中（自己踩过）
  const PROP = /(?<![\w-])(flex-direction|width|align-items|padding|overflow[a-z-]*|flex|min-width|gap|height|white-space)\s*:/g;
  const layoutProps = CSS.match(PROP) || [];
  const importantProps = CSS.match(
    /(?<![\w-])(flex-direction|width|align-items|padding|overflow[a-z-]*|flex|min-width|gap|height|white-space)\s*:[^;]*!important/g) || [];
  eq('布局属性全部带 !important', layoutProps.length, importantProps.length);
  ok('布局属性数量合理（>15）', layoutProps.length > 15, `实际 ${layoutProps.length}`);

  // 自检脚本：无 document 环境下必须安静跳过（同一个文件也会进 worker 侧）
  let layoutThrew = false;
  try { (0, eval)(LAYOUT_CHECK); } catch (e) { layoutThrew = true; }
  ok('布局自检脚本在无 document 环境下不抛异常', !layoutThrew);
}

console.log('\n---------- A. 存在性与语义 ----------');

// Iterator
ok('Iterator 已存在', typeof globalThis.Iterator === 'function');
{
  const it = Iterator.from([1, 2, 3, 4, 5]);
  eq('Iterator.map/filter/toArray', it.map((x) => x * 2).filter((x) => x > 4).toArray(), [6, 8, 10]);
  eq('Iterator.take/drop', Iterator.from([1, 2, 3, 4, 5]).drop(1).take(2).toArray(), [2, 3]);
  eq('Iterator.flatMap', Iterator.from([1, 2]).flatMap((x) => [x, x * 10]).toArray(), [1, 10, 2, 20]);
  eq('Iterator.join', Iterator.from(['a', 'b']).join('-'), 'a-b');
  eq('Iterator.concat', Iterator.concat([1, 2], [3]).toArray(), [1, 2, 3]);
  eq('Iterator reduce/some/every/find', [
    Iterator.from([1, 2, 3]).reduce((a, b) => a + b, 0),
    Iterator.from([1, 2, 3]).some((x) => x === 2),
    Iterator.from([1, 2, 3]).every((x) => x > 0),
    Iterator.from([1, 2, 3]).find((x) => x > 1),
  ], [6, true, true, 2]);
  // PDF.js 的那句特性检测必须能过
  let detected = false;
  try {
    detected = 'function' === typeof Iterator.prototype.join;
  } catch (e) { detected = 'throw:' + e.message; }
  ok('PDF.js 特性检测（Iterator.prototype.join）通过', detected === true, String(detected));
}

// Promise
ok('Promise.withResolvers 已存在', typeof Promise.withResolvers === 'function');
{
  const { promise, resolve } = Promise.withResolvers();
  resolve(42);
  ok('Promise.withResolvers 可用', await promise === 42);
}
ok('Promise.try 已存在', typeof Promise.try === 'function');
{
  eq('Promise.try 同步返回值', await Promise.try(() => 7), 7);
  eq('Promise.try 透传参数', await Promise.try((a, b) => a + b, 3, 4), 7);
  let rejected = false;
  await Promise.try(() => { throw new Error('boom'); }).catch(() => { rejected = true; });
  ok('Promise.try 同步抛异常 → reject', rejected);
  eq('Promise.try 异步返回 promise', await Promise.try(async () => 9), 9);
}

// URL.parse
ok('URL.parse 已存在', typeof URL.parse === 'function');
{
  const u = URL.parse('https://example.com/a?b=1');
  ok('URL.parse 解析成功返回 URL', u instanceof URL && u.hostname === 'example.com');
  eq('URL.parse 非法输入返回 null（不抛异常）', URL.parse('not a url', 'also bad'), null);
  const rel = URL.parse('/x', 'https://example.com/base/');
  eq('URL.parse 支持 base', rel && rel.href, 'https://example.com/x');
}

// RegExp.escape
ok('RegExp.escape 已存在', typeof RegExp.escape === 'function');
{
  eq('RegExp.escape 转义点号（首字符也按规范十六进制转义）', RegExp.escape('a.b'), '\\x61\\.b');
  eq('RegExp.escape 空串', RegExp.escape(''), '');
  eq('RegExp.escape 首字符数字用 \\x 形式', RegExp.escape('1a'), '\x5cx31a');
  const src = 'price: 3.5 (USD) [x]';
  const re = new RegExp(RegExp.escape(src));
  ok('RegExp.escape 结果可直接构造正则并字面匹配', re.test(src) && !re.test('price: 3X5 (USD) [x]'));
}

// Math.sumPrecise
ok('Math.sumPrecise 已存在', typeof Math.sumPrecise === 'function');
{
  eq('Math.sumPrecise 基本求和', Math.sumPrecise([1, 2, 3, 4]), 10);
  eq('Math.sumPrecise 空可迭代对象', Math.sumPrecise([]), 0);
  // 朴素累加在这里会丢精度；补偿求和应得到精确的 1
  const tiny = 1e-20;
  const naive = [1, tiny, -1].reduce((a, b) => a + b, 0);
  const precise = Math.sumPrecise([1, tiny, -1]);
  eq('Math.sumPrecise 精度优于朴素累加', [naive === 0, precise === tiny], [true, true]);
  eq('Math.sumPrecise 支持 Set', Math.sumPrecise(new Set([2, 3])), 5);
  let threw = false;
  try { Math.sumPrecise([1, 'x']); } catch (e) { threw = e instanceof TypeError; }
  ok('Math.sumPrecise 非 number 元素抛 TypeError', threw);
}

// Uint8Array base64 / hex
ok('Uint8Array.fromBase64 已存在', typeof Uint8Array.fromBase64 === 'function');
{
  eq('fromBase64 解码标准串', Array.from(Uint8Array.fromBase64('SGVsbG8=')), [72, 101, 108, 108, 111]);
  eq('fromBase64 无填充也可解', Array.from(Uint8Array.fromBase64('SGVsbG8')), [72, 101, 108, 108, 111]);
  eq('base64url alphabet', Array.from(Uint8Array.fromBase64('-_8=', { alphabet: 'base64url' })), [251, 255]);
  eq('toBase64 往返（原始）', new Uint8Array([251, 255]).toBase64(), '+/8=');
  eq('toBase64 base64url', new Uint8Array([251, 255]).toBase64({ alphabet: 'base64url' }), '-_8=');
  eq('toBase64 omitPadding', new Uint8Array([72, 105]).toBase64({ omitPadding: true }), 'SGk');
  // 多长度往返
  let roundTrip = true;
  for (let n = 0; n < 40; n++) {
    const src = new Uint8Array(n);
    for (let i = 0; i < n; i++) src[i] = (i * 37 + 11) & 255;
    const back = Uint8Array.fromBase64(src.toBase64());
    if (back.length !== n || back.some((v, i) => v !== src[i])) { roundTrip = false; break; }
  }
  ok('base64 往返 0~39 字节全对', roundTrip);
  eq('toHex', new Uint8Array([0, 15, 16, 255]).toHex(), '000f10ff');
  eq('fromHex', Array.from(Uint8Array.fromHex('000f10ff')), [0, 15, 16, 255]);
  eq('toHex/fromHex 往返', Uint8Array.fromHex(new Uint8Array([1, 2, 250]).toHex()).length, 3);
  let badHex = false;
  try { Uint8Array.fromHex('abc'); } catch (e) { badHex = e instanceof SyntaxError; }
  ok('fromHex 奇数长度抛 SyntaxError', badHex);
  const target = new Uint8Array(4);
  const r = target.setFromBase64('SGk=');
  eq('setFromBase64 写入并返回 {read,written}', [r.written, target[0], target[1]], [2, 72, 105]);
  const target2 = new Uint8Array(2);
  target2.setFromHex('0aff');
  eq('setFromHex 写入', [target2[0], target2[1]], [10, 255]);
}

// bytes()
for (const [label, obj, expect] of [
  ['Blob', new Blob([new Uint8Array([1, 2, 3])]), [1, 2, 3]],
  ['Response', new Response(new Uint8Array([4, 5, 6])), [4, 5, 6]],
  ['Request', new Request('http://x/', { method: 'POST', body: new Uint8Array([7, 8]) }), [7, 8]],
]) {
  const fn = obj.bytes;
  ok(`${label}.prototype.bytes 已存在`, typeof fn === 'function');
  const got = await fn.call(obj);
  ok(`${label}.bytes() 返回 Uint8Array`, got instanceof Uint8Array, got?.constructor?.name);
  eq(`${label}.bytes() 内容`, Array.from(got), expect);
  // 关键组合：pdf.js 就是 bytes() 之后再 .toBase64()
  eq(`${label}.bytes().toBase64() 组合可用`, got.toBase64(), Buffer.from(expect).toString('base64'));
  ok(`${label}.prototype.bytes 不可枚举（不污染 for-in）`,
    Object.getOwnPropertyDescriptor(Object.getPrototypeOf(obj), 'bytes')?.enumerable === false);
}

// 其余保留项
ok('AbortSignal.any 已存在', typeof AbortSignal.any === 'function');
ok('AbortSignal.timeout 已存在', typeof AbortSignal.timeout === 'function');
ok('Object.groupBy 已存在', typeof Object.groupBy === 'function');
ok('Map.groupBy 已存在', typeof Map.groupBy === 'function');
ok('Array.fromAsync 已存在', typeof Array.fromAsync === 'function');
ok('Symbol.dispose 已存在', typeof Symbol.dispose === 'symbol');
ok('Symbol.asyncDispose 已存在', typeof Symbol.asyncDispose === 'symbol');
{
  eq('Set.union', [...new Set([1, 2]).union(new Set([2, 3]))], [1, 2, 3]);
  eq('Set.intersection', [...new Set([1, 2]).intersection(new Set([2, 3]))], [2]);
  eq('Set.difference', [...new Set([1, 2]).difference(new Set([2, 3]))], [1]);
  eq('Set.symmetricDifference', [...new Set([1, 2]).symmetricDifference(new Set([2, 3]))], [1, 3]);
  ok('Set.isSubsetOf', new Set([1]).isSubsetOf(new Set([1, 2])));
  ok('Set.isSupersetOf', new Set([1, 2]).isSupersetOf(new Set([1])));
  ok('Set.isDisjointFrom', new Set([1]).isDisjointFrom(new Set([2])));
  eq('Object.groupBy 语义', Object.groupBy([1, 2, 3, 4], (x) => (x % 2 ? 'odd' : 'even')).odd, [1, 3]);
}
{
  const c = new AbortController();
  const any = AbortSignal.any([c.signal]);
  c.abort('why');
  ok('AbortSignal.any 传播 abort', any.aborted === true);
}

console.log('\n---------- B. 幂等 / 不覆盖原生 ----------');

// B1：再跑一遍不应该报错，也不应该把已补的实现换掉
const marker = Iterator;
(0, eval)(JS);
ok('第二次执行垫片不抛异常，且不替换已补的 Iterator', globalThis.Iterator === marker);
ok('第二次执行后 Uint8Array.toBase64 仍可用', new Uint8Array([1]).toBase64() === 'AQ==');

// B2：已存在原生实现时不得覆盖（模拟新内核）
{
  const sentinel = function nativeEscape() { return 'NATIVE'; };
  const holder = RegExp;
  const saved = holder.escape;
  holder.escape = sentinel;
  (0, eval)(JS);
  ok('原生已存在时垫片不覆盖（本例：RegExp.escape）', holder.escape === sentinel);
  holder.escape = saved;
}

// B3：守卫在无 document 环境（worker）里必须安静跳过
let guardThrew = false;
try { (0, eval)(GUARD); } catch (e) { guardThrew = true; }
ok('worker 守卫在无 document 环境下不抛异常', !guardThrew);

console.log('\n---------- C. 与原生实现差分对拍 ---------');
{
  let mismatches = 0, first = null;
  const cmp = (label, mine, native) => {
    const a = JSON.stringify(mine), b = JSON.stringify(native);
    if (a !== b) { mismatches++; if (!first) first = `${label}\n      垫片 ${a}\n      原生 ${b}`; }
  };

  if (nativeRef.hasEscape) {
    CORPUS.forEach((s, i) => cmp(`RegExp.escape(${JSON.stringify(s)})`, RegExp.escape(s), nativeRef.escape[i]));
    ok(`RegExp.escape 与原生完全一致（${CORPUS.length} 条样本）`, mismatches === 0, first);
  } else {
    console.log('  （本机 Node 没有原生 RegExp.escape，跳过该项对拍）');
  }

  if (nativeRef.hasBase64) {
    let m2 = 0, f2 = null;
    BYTES_CORPUS.forEach((b, i) => {
      const mine = b.toBase64();
      if (mine !== nativeRef.toBase64[i]) { m2++; if (!f2) f2 = `第${i}条 ${b.length}字节: 垫片 ${mine} / 原生 ${nativeRef.toBase64[i]}`; }
      const back = Array.from(Uint8Array.fromBase64(mine));
      if (JSON.stringify(back) !== JSON.stringify(nativeRef.fromBase64[i])) { m2++; if (!f2) f2 = `第${i}条回环不一致`; }
    });
    ok(`toBase64 与原生一致（${BYTES_CORPUS.length} 条样本）`, m2 === 0, f2);
  } else {
    console.log('  （本机 Node 没有原生 toBase64，跳过该项对拍）');
  }

  if (nativeRef.hasHex) {
    let m3 = 0, f3 = null;
    BYTES_CORPUS.forEach((b, i) => {
      if (b.toHex() !== nativeRef.toHex[i]) { m3++; if (!f3) f3 = `第${i}条: 垫片 ${b.toHex()} / 原生 ${nativeRef.toHex[i]}`; }
    });
    ok(`toHex 与原生一致（${BYTES_CORPUS.length} 条样本）`, m3 === 0, f3);
  }

  if (nativeRef.hasSum) {
    let m4 = 0, f4 = null;
    NUM_CORPUS.forEach((xs, i) => {
      const mine = Math.sumPrecise(xs);
      if (!Object.is(mine, nativeRef.sum[i])) { m4++; if (!f4) f4 = `第${i}条: 垫片 ${mine} / 原生 ${nativeRef.sum[i]}`; }
    });
    ok(`Math.sumPrecise 与原生一致（${NUM_CORPUS.length} 条样本）`, m4 === 0, f4);
  } else {
    console.log('  （本机 Node 没有原生 Math.sumPrecise，跳过该项对拍）');
  }

  if (nativeRef.hasUrlParse) {
    let m5 = 0, f5 = null;
    CORPUS.forEach((s, i) => {
      const u = URL.parse(s, 'https://example.com/base/');
      const mine = u === null ? null : u.href;
      if (mine !== nativeRef.urlParse[i]) { m5++; if (!f5) f5 = `${JSON.stringify(s)}: 垫片 ${mine} / 原生 ${nativeRef.urlParse[i]}`; }
    });
    ok(`URL.parse 与原生一致（${CORPUS.length} 条样本）`, m5 === 0, f5);
  } else {
    console.log('  （本机 Node 没有原生 URL.parse，跳过该项对拍）');
  }
}

console.log('\n========== 结果 ==========');
console.log(`通过 ${pass} / 失败 ${fail}`);
if (fail) {
  console.log('\n失败项：');
  for (const f of failures) console.log('  ✗ ' + f);
  process.exit(1);
}
console.log('全部通过');
