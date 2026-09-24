// =============================================================================
// APTUIDSH · 非特殊 scheme authority 补丁的差分测试
// -----------------------------------------------------------------------------
// 真机（Chrome/114.0.5735.196 WebView）实测：
//   new URL('dsh-resource://file/session/sid/a.js')
//     .hostname === ''                          ← 应为 'file'
//     .pathname === '//file/session/sid/a.js'   ← 应为 '/session/sid/a.js'
// 本机 Node 的 URL 是**规范实现**，所以必须**先把它换成模拟旧内核的假体**，
// 才能验证垫片真的补上了这个缺口；否则测出来的只是「Node 本来就对」的假绿。
//
// 两组对照都要过：
//   A. 旧内核假体 + 垫片 → 缺口被补上，且 dsh 的 protocolOf() 判定恢复正确
//   B. 规范 URL（Node 原生）+ 垫片 → 特性探测直接短路，一个字节都不改（零副作用）
// =============================================================================
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const KT = path.join(HERE, '..', '..', 'app/src/main/java/com/aptuidsh/kui/WebPolyfill.kt');
const kt = fs.readFileSync(KT, 'utf8');
const m = /private const val JS = """\n([\s\S]*?)\n"""/.exec(kt);
if (!m) { console.log('FAIL: 抽不到 JS 垫片源码'); process.exit(1); }
const POLYFILL = m[1];

let ok = 0, bad = 0;
const t = (name, cond, extra) => {
  if (cond) { ok += 1; console.log('  ok   ' + name); }
  else { bad += 1; console.log('  FAIL ' + name + (extra === undefined ? '' : '  → ' + extra)); }
};

const RealURL = globalThis.URL;
const ADDR = 'dsh-resource://file/session/session-932f98d1-ad85-4d50-9638-fee4aa9cfa6b/make-test-image.js';

// ---------------------------------------------------------------- 旧内核假体
// 只复刻我们实测到的那一个偏差：non-special scheme 不解析 authority。
function makeBrokenURL() {
  const SPECIAL = /^(https?|ftp|file|wss?):$/i;
  function BrokenURL(input, base) {
    const u = arguments.length > 1 ? new RealURL(input, base) : new RealURL(input);
    // 注意：不能写成 this.host = …，那会撞上原生 URL.prototype 的 host setter。
    const hasAuthority = /^([A-Za-z][A-Za-z0-9+.-]*):\/\//.test(String(input));
    const parsedAuthority = hasAuthority && !SPECIAL.test(u.protocol);
    Object.defineProperty(this, '_u', { value: u, writable: true });
    Object.defineProperty(this, '_h', { value: parsedAuthority ? '' : u.hostname, writable: true });
    Object.defineProperty(this, '_p', {
      value: parsedAuthority ? '//' + u.hostname + u.pathname : u.pathname, writable: true,
    });
  }
  BrokenURL.prototype = Object.create(RealURL.prototype);
  const g = (name, fn) => Object.defineProperty(BrokenURL.prototype, name, { configurable: true, get: fn });
  g('href', function () { return this._u.href; });
  g('protocol', function () { return this._u.protocol; });
  g('hostname', function () { return this._h; });
  g('pathname', function () { return this._p; });
  return BrokenURL;
}

/** dsh-client-resources 里 protocolOf() 的等价实现，用来判定「地址归谁管」。 */
const protocolOf = (URLImpl, address) => {
  let parsed;
  try { parsed = new URLImpl(address); } catch { return undefined; }
  if (parsed.protocol !== 'dsh-resource:') return undefined;
  return parsed.hostname === '' ? undefined : parsed.hostname.toLowerCase();
};

// ------------------------------------------------------------------ A. 旧内核
console.log('\n--- A. 模拟 Chrome 114 旧内核（authority 不解析）+ 垫片 ---');
globalThis.URL = makeBrokenURL();
t('假体确实复刻了缺口：hostname 为空', new URL(ADDR).hostname === '', JSON.stringify(new URL(ADDR).hostname));
t('假体确实复刻了缺口：pathname 多出 //file', new URL(ADDR).pathname.indexOf('//file') === 0, new URL(ADDR).pathname.slice(0, 20));
t('假体下 dsh 的 protocolOf 判定为 undefined（即「文件资源服务不可用」）', protocolOf(URL, ADDR) === undefined);

new Function(POLYFILL)();                        // 装垫片
t('垫片后 hostname 恢复为 file', new URL(ADDR).hostname === 'file', JSON.stringify(new URL(ADDR).hostname));
t('垫片后 pathname 恢复为 /session/...',
  new URL(ADDR).pathname === '/session/session-932f98d1-ad85-4d50-9638-fee4aa9cfa6b/make-test-image.js',
  new URL(ADDR).pathname);
t('垫片后 dsh 的 protocolOf 判定为 file（修复生效）', protocolOf(URL, ADDR) === 'file', String(protocolOf(URL, ADDR)));
t('带端口的 authority 也被正确剥端口',
  new URL('dsh-resource://file:8080/a/b').hostname === 'file', new URL('dsh-resource://file:8080/a/b').hostname);
t('带 userinfo 的 authority 也被正确剥掉',
  new URL('dsh-resource://u:p@file/a').hostname === 'file', new URL('dsh-resource://u:p@file/a').hostname);
t('特殊 scheme 的双斜杠路径不被误伤：http://a//b 的 pathname 仍是 //b',
  new URL('http://a//b').pathname === '//b', new URL('http://a//b').pathname);
t('没有 //authority 的串不被误伤：foo:/bar 的 hostname 仍为空', new URL('foo:/bar').hostname === '');
t('data: 之类不被误伤', new URL('data:text/plain,hello').hostname === '');
t('垫片幂等：再装一次结果不变', (() => { new Function(POLYFILL)(); return new URL(ADDR).hostname === 'file'; })());

// ------------------------------------------------------------------ B. 规范内核
console.log('\n--- B. 规范内核（Node 原生 URL）+ 垫片：必须零副作用 ---');
globalThis.URL = RealURL;
const before = Object.getOwnPropertyDescriptor(RealURL.prototype, 'hostname').get;
new Function(POLYFILL)();
const after = Object.getOwnPropertyDescriptor(RealURL.prototype, 'hostname').get;
t('特性探测短路：根本没替换 hostname getter', before === after);
t('规范内核行为不受影响', new URL(ADDR).hostname === 'file' && new URL(ADDR).pathname.indexOf('//file') !== 0);
t('规范内核 protocolOf 本来就正确', protocolOf(URL, ADDR) === 'file');

console.log('\n  --- URL authority 差分测试：通过 ' + ok + ' / 失败 ' + bad);
process.exit(bad === 0 ? 0 : 1);
