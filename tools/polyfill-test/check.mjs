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
  eq('RegExp.escape 转义点号', RegExp.escape('a.b'), 'a\\.b');
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

console.log('\n========== 结果 ==========');
console.log(`通过 ${pass} / 失败 ${fail}`);
if (fail) {
  console.log('\n失败项：');
  for (const f of failures) console.log('  ✗ ' + f);
  process.exit(1);
}
console.log('全部通过');
