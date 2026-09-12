package com.aptuidsh.kui

/**
 * 注入到官方 Web UI 页面最前面的兼容性垫片。
 *
 * <h3>为什么需要它</h3>
 * dsh 自带的 `dsh-client-ui-sidebar-documentpreview` 里内嵌了 PDF.js，
 * 其中有一句特性检测：
 * <pre>
 * "function" != typeof Iterator.prototype.join &amp;&amp; (Iterator.prototype.join = function (e) { return [...this].join(e) })
 * </pre>
 * `Iterator` 是 **Chromium 122（2024-02）才引入的 JS 全局对象**（Iterator Helpers）。
 * 在版本较旧的系统 WebView 上它是 `undefined`，于是 `Iterator.prototype` 直接抛
 * `ReferenceError: Iterator is not defined`，表现为官方界面一片报错：
 * <pre>
 * Failed to load plugins
 * failed to import loader entry … (@deepseek-ai/dsh-client-ui-sidebar-documentpreview): Iterator is not defined
 * </pre>
 *
 * <p>全 dsh 客户端包里只有这一处用到 `Iterator`，所以补上这个全局对象即可。
 * 这里给出一个语义基本完整的 Iterator Helpers 实现（惰性求值），
 * 而不只是让 `prototype` 存在——避免后续版本真的用到这些方法时再出问题。
 *
 * <p>此外还补齐了同一批"旧内核缺失 API"（`Promise.withResolvers`、`AbortSignal.any`、
 * `Object.groupBy`、`Symbol.dispose`、`Set` 集合运算、`Array.fromAsync` 等）。
 * 目标内核实测为 **Chrome 114**（华为机型系统 WebView），而 dsh 官方界面按现代浏览器构建，
 * 这些 API 分布在 116~122 各版本。全部按"存在则跳过"补齐，新内核上无副作用。
 *
 * <p>注入方式见 [WebUiActivity]：用 `shouldInterceptRequest` 拦下根文档，
 * 把本脚本插到 `<head>` 之后，确保它**先于任何模块脚本执行**。
 */
object WebPolyfill {

    /** 注入用的内联脚本（含 `<script>` 标签）。 */
    val SCRIPT: String = buildString {
        append("<script data-aptuidsh=\"iterator-polyfill\">")
        append(JS)
        append("</script>")
    }

    /** 在 HTML 里尽早插入垫片；插不进去就前置到文档最前面。 */
    fun inject(html: String): String {
        val lower = html.lowercase()
        val headIdx = lower.indexOf("<head")
        if (headIdx >= 0) {
            val close = lower.indexOf('>', headIdx)
            if (close > 0) {
                return html.substring(0, close + 1) + SCRIPT + html.substring(close + 1)
            }
        }
        val scriptIdx = lower.indexOf("<script")
        if (scriptIdx >= 0) {
            return html.substring(0, scriptIdx) + SCRIPT + html.substring(scriptIdx)
        }
        return SCRIPT + html
    }

    private const val JS = """
(function () {
  var g = typeof globalThis !== 'undefined' ? globalThis : (typeof self !== 'undefined' ? self : this);
  if (g.Iterator && g.Iterator.prototype) { return; }

  function Iterator() { throw new TypeError('Illegal constructor'); }
  var proto = Object.create(Object.prototype);

  function wrapIterator(next) {
    var o = Object.create(proto);
    o.__next = next;
    return o;
  }
  function fromIterable(src) {
    if (src && typeof src.next === 'function') { return wrapIterator(function () { return src.next(); }); }
    var it = src[Symbol.iterator]();
    return wrapIterator(function () { return it.next(); });
  }

  proto.next = function () { return this.__next(); };
  /** 把本迭代器拆成原生 iterator 协议对象，供内部组合使用。 */
  proto.__iter = function () { var self = this; return { next: function () { return self.__next(); } }; };
  proto[Symbol.iterator] = function () { return this; };
  proto.map = function (f) {
    var self = this, i = 0;
    return wrapIterator(function () {
      var r = self.__next();
      if (r.done) { return r; }
      return { value: f(r.value, i++), done: false };
    });
  };
  proto.filter = function (f) {
    var self = this, i = 0;
    return wrapIterator(function () {
      for (;;) {
        var r = self.__next();
        if (r.done) { return r; }
        if (f(r.value, i++)) { return r; }
      }
    });
  };
  proto.take = function (n) {
    var self = this, left = n;
    return wrapIterator(function () {
      if (left <= 0) { return { value: undefined, done: true }; }
      left--;
      return self.__next();
    });
  };
  proto.drop = function (n) {
    var self = this, left = n;
    return wrapIterator(function () {
      while (left > 0) { var r0 = self.__next(); if (r0.done) { return r0; } left--; }
      return self.__next();
    });
  };
  proto.flatMap = function (f) {
    var self = this, inner = null, i = 0;
    return wrapIterator(function () {
      for (;;) {
        if (inner) {
          var r1 = inner.next();
          if (!r1.done) { return r1; }
          inner = null;
        }
        var r = self.__next();
        if (r.done) { return r; }
        inner = fromIterable(f(r.value, i++)).__iter();
      }
    });
  };
  proto.toArray = function () { var out = []; for (var r = this.__next(); !r.done; r = this.__next()) { out.push(r.value); } return out; };
  proto.forEach = function (f) { var i = 0; for (var r = this.__next(); !r.done; r = this.__next()) { f(r.value, i++); } };
  proto.reduce = function (f, init) {
    var acc = init, started = arguments.length > 1;
    for (var r = this.__next(); !r.done; r = this.__next()) {
      if (!started) { acc = r.value; started = true; } else { acc = f(acc, r.value); }
    }
    if (!started) { throw new TypeError('Reduce of empty iterator with no initial value'); }
    return acc;
  };
  proto.some = function (f) { var i = 0; for (var r = this.__next(); !r.done; r = this.__next()) { if (f(r.value, i++)) { return true; } } return false; };
  proto.every = function (f) { var i = 0; for (var r = this.__next(); !r.done; r = this.__next()) { if (!f(r.value, i++)) { return false; } } return true; };
  proto.find = function (f) { var i = 0; for (var r = this.__next(); !r.done; r = this.__next()) { if (f(r.value, i++)) { return r.value; } } return undefined; };
  proto.join = function (sep) { return this.toArray().join(sep); };

  Iterator.prototype = proto;
  Iterator.from = function (src) { return fromIterable(src); };
  Iterator.concat = function () {
    var sources = Array.prototype.slice.call(arguments), si = 0, inner = null;
    return wrapIterator(function () {
      for (;;) {
        if (inner) { var r1 = inner.next(); if (!r1.done) { return r1; } inner = null; }
        if (si >= sources.length) { return { value: undefined, done: true }; }
        inner = fromIterable(sources[si++]).__iter();
      }
    });
  };

  g.Iterator = Iterator;
  if (typeof g.Symbol !== 'undefined' && !g.Symbol.iterator) { g.Symbol.iterator = g.Symbol('Symbol.iterator'); }
})();

// =====================================================================
// 以下是同一批"旧内核缺失 API"的垫片。
// 目标内核是 Android 系统 WebView（实测华为机型为 Chrome 114），
// 而 dsh 官方界面按现代浏览器构建，用到了一批 116~122 才有的 API：
//   Chrome 116 : AbortSignal.any
//   Chrome 117 : Object.groupBy / Map.groupBy
//   Chrome 119 : Promise.withResolvers、Symbol.dispose（using 声明）
//   Chrome 121 : Array.fromAsync
//   Chrome 122 : Iterator、Set 集合运算方法
// 全部按"存在则跳过"的方式补齐，新内核上不产生任何副作用。
// =====================================================================
(function () {
  var g = typeof globalThis !== 'undefined' ? globalThis : this;

  // --- Promise.withResolvers (Chrome 119) ---
  if (typeof Promise !== 'undefined' && typeof Promise.withResolvers !== 'function') {
    Promise.withResolvers = function () {
      var resolve, reject;
      var promise = new Promise(function (res, rej) { resolve = res; reject = rej; });
      return { promise: promise, resolve: resolve, reject: reject };
    };
  }

  // --- AbortSignal.any / AbortSignal.timeout (Chrome 116) ---
  if (typeof AbortSignal !== 'undefined') {
    if (typeof AbortSignal.any !== 'function') {
      AbortSignal.any = function (signals) {
        var list = Array.prototype.slice.call(signals);
        var controller = new AbortController();
        function abort(reason) {
          if (!controller.signal.aborted) { controller.abort(reason); }
        }
        for (var i = 0; i < list.length; i++) {
          var s = list[i];
          if (!s) { continue; }
          if (s.aborted) { abort(s.reason); break; }
          s.addEventListener('abort', (function (sig) {
            return function () { abort(sig.reason); };
          })(s), { once: true });
        }
        return controller.signal;
      };
    }
    if (typeof AbortSignal.timeout !== 'function') {
      AbortSignal.timeout = function (ms) {
        var controller = new AbortController();
        setTimeout(function () {
          controller.abort(new DOMException('TimeoutError', 'TimeoutError'));
        }, ms);
        return controller.signal;
      };
    }
  }

  // --- Object.groupBy / Map.groupBy (Chrome 117) ---
  if (typeof Object.groupBy !== 'function') {
    Object.groupBy = function (items, keyFn) {
      var out = Object.create(null);
      var i = 0;
      for (var it of items) {
        var k = keyFn(it, i++);
        (out[k] || (out[k] = [])).push(it);
      }
      return out;
    };
  }
  if (typeof Map.groupBy !== 'function') {
    Map.groupBy = function (items, keyFn) {
      var out = new Map();
      var i = 0;
      for (var it of items) {
        var k = keyFn(it, i++);
        if (!out.has(k)) { out.set(k, []); }
        out.get(k).push(it);
      }
      return out;
    };
  }

  // --- Array.fromAsync (Chrome 121) ---
  if (typeof Array.fromAsync !== 'function') {
    Array.fromAsync = function (items, mapFn) {
      return (async function () {
        var out = [];
        var i = 0;
        for await (var it of items) {
          out.push(mapFn ? await mapFn(it, i++) : it);
        }
        return out;
      })();
    };
  }

  // --- Symbol.dispose / Symbol.asyncDispose (Chrome 119) ---
  // dsh 打包产物用 __addDisposableResource 实现 `using` 声明；
  // 缺少这两个 well-known symbol 会让相关 helper 直接失败。
  if (typeof Symbol !== 'undefined') {
    if (typeof Symbol.dispose === 'undefined') {
      Symbol.dispose = Symbol('Symbol.dispose');
    }
    if (typeof Symbol.asyncDispose === 'undefined') {
      Symbol.asyncDispose = Symbol('Symbol.asyncDispose');
    }
  }

  // --- Set 集合运算 (Chrome 122) ---
  if (typeof Set !== 'undefined' && typeof Set.prototype.union !== 'function') {
    function toSet(x) { return x instanceof Set ? x : new Set(x); }
    Set.prototype.union = function (other) {
      var out = new Set(this); for (var v of toSet(other)) { out.add(v); } return out;
    };
    Set.prototype.intersection = function (other) {
      var o = toSet(other), out = new Set();
      for (var v of this) { if (o.has(v)) { out.add(v); } } return out;
    };
    Set.prototype.difference = function (other) {
      var o = toSet(other), out = new Set();
      for (var v of this) { if (!o.has(v)) { out.add(v); } } return out;
    };
    Set.prototype.symmetricDifference = function (other) {
      var o = toSet(other), out = new Set();
      for (var v of this) { if (!o.has(v)) { out.add(v); } }
      for (var w of o) { if (!this.has(w)) { out.add(w); } } return out;
    };
    Set.prototype.isSubsetOf = function (other) {
      var o = toSet(other); for (var v of this) { if (!o.has(v)) { return false; } } return true;
    };
    Set.prototype.isSupersetOf = function (other) {
      for (var v of toSet(other)) { if (!this.has(v)) { return false; } } return true;
    };
    Set.prototype.isDisjointFrom = function (other) {
      var o = toSet(other); for (var v of this) { if (o.has(v)) { return false; } } return true;
    };
  }
})();
"""
}
