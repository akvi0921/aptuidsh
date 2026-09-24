package com.aptuidsh.kui

/**
 * 注入到官方 Web UI 页面最前面的兼容性垫片。
 *
 * <h3>为什么需要它</h3>
 * dsh 自带的官方 Web UI 按**现代浏览器**构建，而本机机型的系统 WebView 实测只有
 * **Chrome 114**（Chromium 122 才引入 `Iterator`，140 才引入 `Uint8Array` 的
 * base64/hex 方法）。缺的 API 会让客户端模块**导入即抛异常**，表现为整页
 * 「Failed to load plugins」，或者某个功能页（如 PDF 预览）打不开。
 *
 * <p>这些 API 全都是**纯 JS 标准库 API**，可以用垫片完全覆盖，代价远小于换内核
 * （Android 也不允许应用自带 Chromium）。全部按「存在则跳过」补齐，新内核上零副作用。
 *
 * <h3>0.1.7-rc.1 升级后重新体检的结论（2026-09-24）</h3>
 * 做法：从 MDN browser-compat-data 导出「Chrome &gt; 114 才加入」的全部 JS 内建/Web API，
 * 再对 dsh 真正发给浏览器的 **84 个脚本（22.6 MB）** 做穷举匹配，逐条回到源码上下文判定真伪。
 * 结论：
 * <ul>
 *   <li><b>启动链路（Web UI 外壳 + 各插件 lib/client.js）已无缺口</b> —— 上一轮补的
 *       `Iterator` / `Promise.withResolvers` / `Symbol.dispose` 等正好覆盖。</li>
 *   <li><b>PDF 预览链路新增了一批缺口</b>（都在懒加载的 `client.pdf.js` 与它内嵌的
 *       worker 里）：`URL.parse`(126)、`Promise.try`(128)、`RegExp.escape`(136)、
 *       `Math.sumPrecise`(147)、`Uint8Array.fromBase64/toBase64/toHex`(140)、
 *       `Blob/Response.prototype.bytes()`(132/144)。本轮全部补齐。</li>
 *   <li><b>PDF.js 的 worker 是独立全局作用域</b>，页面里的垫片到不了它 —— 而它同样用到
 *       上面这些 API（`Math.sumPrecise` 16 处）。见下面的「worker 注入守卫」。</li>
 *   <li><b>已确认不需要补</b>（源码里有特性检测或只是注释里的词）：`Float16Array`、
 *       `Temporal`、`Sanitizer`、`document.caretPositionFromPoint`；
 *       以及纯 CSS 的 `@starting-style`(117) —— CSS 缺失只会降级、不会报错。</li>
 * </ul>
 *
 * <h3>注入方式</h3>
 * 见 [WebUiActivity]：用 `shouldInterceptRequest` 拦下根文档，把 [SCRIPT] 插到
 * `<head>` 之后。外壳的入口是 `type="module"` 脚本（默认 defer，等文档解析完才执行），
 * 而这里的垫片是内联经典脚本，**必然先于它执行**。
 *
 * <h3>worker 注入守卫</h3>
 * pdf.js 的 worker 由 `new Worker(URL.createObjectURL(new Blob([源码], {type:"text/javascript"})))`
 * 创建 —— 是**独立的全局作用域**，页面里的垫片对它无效。因此这里额外包一层 `Blob`：
 * 只在「type 是 JS 且 parts 全是字符串」时，把垫片源码前置进那段 blob。
 * 垫片本身幂等无副作用，所以对任何 JS blob 前置都是安全的；
 * 任何异常都吞掉并退化成「worker 没有垫片」，绝不影响页面本身。
 */
object WebPolyfill {

    /** 承载垫片源码的元素 id：worker 注入守卫靠它拿到同一份源码（避免源码写两遍）。 */
    const val ELEMENT_ID = "__aptuidsh_polyfill__"

    /** 注入用的内联脚本（垫片本体 + worker 注入守卫）。 */
    val SCRIPT: String = buildString {
        append("<script data-aptuidsh=\"polyfill\" id=\"")
        append(ELEMENT_ID)
        append("\">")
        append(JS)
        append("</script>")
        append("<script data-aptuidsh=\"worker-bridge\">")
        append(GUARD)
        append("</script>")
        append("<style data-aptuidsh=\"layout\">")
        append(CSS)
        append("</style>")
        append("<script data-aptuidsh=\"layout-check\">")
        append(LAYOUT_CHECK)
        append("</script>")
        append("<script data-aptuidsh=\"error-capture\">")
        append(ERROR_CAPTURE)
        append("</script>")
        append("<script data-aptuidsh=\"resource-probe\">")
        append(RESOURCE_PROBE)
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

    /**
     * worker 注入守卫：把 [JS] 前置进「JS 类型的 Blob」，好让由 blob URL 创建的
     * Worker（pdf.js 的 worker 就是）也带上垫片。
     *
     * <p>刻意写得非常保守：只认 `type` 明确是 JS、且 `parts` **全是字符串**的 Blob；
     * 其余一律原样交给原生 `Blob`。同时保留 `prototype` 与静态成员，
     * 让 `instanceof Blob` 等判断继续成立。
     */
    private const val GUARD = """
(function () {
  try {
    if (typeof Blob === 'undefined' || typeof document === 'undefined') { return; }
    var el = document.getElementById("__aptuidsh_polyfill__");
    if (!el || typeof el.textContent !== 'string' || el.textContent.length < 64) { return; }
    var Orig = Blob;
    if (Orig.__aptuidshBridged) { return; }

    // 前置时用一个换行 + 分号开头，避免与 blob 自身内容粘连成同一个语句
    var src = '\n;' + el.textContent + '\n';

    function isJsBlob(parts, opts) {
      if (!opts || typeof opts.type !== 'string') { return false; }
      if (!/javascript|ecmascript/i.test(opts.type)) { return false; }
      if (!Array.isArray(parts) || parts.length === 0) { return false; }
      for (var i = 0; i < parts.length; i++) {
        if (typeof parts[i] !== 'string') { return false; }
      }
      return true;
    }

    function BridgedBlob(parts, opts) {
      if (isJsBlob(parts, opts)) { parts = [src].concat(parts); }
      return new Orig(parts, opts);
    }

    BridgedBlob.prototype = Orig.prototype;
    var names = Object.getOwnPropertyNames(Orig);
    for (var i = 0; i < names.length; i++) {
      var k = names[i];
      if (k === 'prototype' || k === 'length' || k === 'name') { continue; }
      try {
        Object.defineProperty(BridgedBlob, k, Object.getOwnPropertyDescriptor(Orig, k));
      } catch (e) { /* 个别属性不可配置，跳过即可 */ }
    }
    try { Object.defineProperty(BridgedBlob, 'name', { value: 'Blob', configurable: true }); } catch (e) {}
    try { Object.defineProperty(Orig, '__aptuidshBridged', { value: true, configurable: true }); } catch (e) {}
    globalThis.Blob = BridgedBlob;
  } catch (e) {
    // 守卫失败只意味着「worker 里没有垫片」，绝不能影响页面本身
  }
})();
"""

    /**
     * 窄屏布局覆盖层：把官方 Web UI 里**为桌面设计的左右布局改成上下布局**。
     *
     * <h3>为什么必须加 !important</h3>
     * 官方界面是桌面优先的：设置弹窗固定 `width:800px`，里面左边一条 **188px 的竖排导航**，
     * 右边才是内容；每条设置行又是 `display:flex; justify-content:space-between`。
     * 在手机上（弹窗可用宽度只有 `100vw - 48px`）内容列被压到几十像素，
     * 而中文的 min-content 就是「一个汉字」，于是标签被逐字换行竖排、控件被挤到最右 —— 
     * 就是实测看到的「设置页变形」。
     *
     * <p>另外 dsh 的插件样式是**运行时** `document.head.appendChild` 注入的，
     * 永远排在我们注入的 `<style>` 之后；同为单类选择器时后者胜出，
     * 所以这里必须 `!important` 才能稳定覆盖。
     *
     * <h3>⚠ 与 dsh 版本的耦合</h3>
     * 选择器用的是 dsh 的 CSS Module 类名（`[class~=...]` 按 token 精确匹配）。
     * **升级内置 dsh 后必须重新核对这几个类名**；[LAYOUT_CHECK] 会在设置弹窗出现时
     * 把「覆盖是否真的生效」写到 `window.__aptuidshSettingsLayout`，用于排查。
     *
     * <p><b>只对手机竖屏生效</b>：横屏时视口够宽，官方原本的左右布局更好用，
     * 所以整段包在 `@media (orientation: portrait) and (max-width: 600px)` 里。
     */
    private const val CSS = """
/* =====================================================================
 * 官方设置弹窗：手机竖屏下由「左右布局」改成「上下布局」
 * ---------------------------------------------------------------------
 * ⚠ 只在【手机竖屏】生效：横屏时视口够宽，官方原本的左右布局更好用，
 *   必须保持原样 —— 所以整段包在 orientation:portrait 里。
 *
 * ⚠ 选择器必须用 [class~="X"]（按 token 精确匹配），**不能用 [class*="X"]**：
 *   class* 是子串匹配，"VOzbGW_nav" 会同时命中 VOzbGW_navTitle / VOzbGW_navList /
 *   VOzbGW_navCell，于是 width:100% 被套到标题上、把后面四个菜单项挤出可视区并被
 *   overflow:hidden 裁掉 —— 表现就是「设置项全不见了」（实测踩过这一刀）。
 * ===================================================================== */
@media (orientation: portrait) and (max-width: 600px) {

  /* 弹窗本体：横向 flex → 纵向（导航在上、内容在下）
     —— 同时把高度钉死在【可见视口】内，否则内容会撑破弹窗被裁掉：
        · dvh 跟的是可见视口，而原来的 100vh 跟的是布局视口（WebView 里两者可能不等）；
        · 先写 vh 再写 dvh，不支持 dvh 的内核会自动丢掉后者、沿用前者。 */
  [class~="VOzbGW_panel"] {
    flex-direction: column !important;
    height: min(800px, calc(100vh - 24px)) !important;
    max-height: calc(100vh - 24px) !important;
    height: min(800px, calc(100dvh - 24px)) !important;
    max-height: calc(100dvh - 24px) !important;
  }

  /* ★ 让内容区能【内部滚动】的关键一步：
     flex 子项的 min-height 默认是 auto（= 内容高度），拒绝收缩 → 它会撑破弹窗、
     然后被 overflow:hidden 裁掉，表现就是「超出部分看不到、又不滚」。
     必须显式 min-height:0 才允许它收缩，里面的 options 才拿得到滚动空间。 */
  [class~="VOzbGW_content"] {
    min-height: 0 !important;
    overflow: hidden !important;
  }
  [class~="VOzbGW_options"] {
    min-height: 0 !important;
    overflow-y: auto !important;
    -webkit-overflow-scrolling: touch !important;
  }

  /* 顶部导航条：原本是固定 188px 的竖列，改成占满整宽的一行 */
  [class~="VOzbGW_nav"] {
    flex-direction: row !important;
    width: 100% !important;
    align-items: center !important;
    gap: 10px !important;
    padding: 14px 12px 6px !important;
    overflow: hidden !important;
  }
  [class~="VOzbGW_navTitle"] {
    flex: none !important;
    padding: 0 4px !important;
  }
  /* 导航项横向排列，放不下就横滑（不换行、不挤压内容区） */
  [class~="VOzbGW_navList"] {
    flex-direction: row !important;
    flex: 1 1 auto !important;
    min-width: 0 !important;
    gap: 4px !important;
    overflow-x: auto !important;
    overflow-y: hidden !important;
  }
  [class~="VOzbGW_navCell"] {
    flex: none !important;
    height: 34px !important;
    white-space: nowrap !important;
  }

  /* 设置行：窄屏下标题/说明在上、控件在下，避免左列被压成「一字一行」 */
  [class~="Pt1bsG_row"] {
    flex-direction: column !important;
    align-items: stretch !important;
    gap: 10px !important;
  }
}
"""

    /**
     * 覆盖层自检：设置弹窗一出现就检查 `flex-direction` 是否真的变成 `column`，
     * 结果写到 `window.__aptuidshSettingsLayout`（`applied` / `stale`），
     * 便于 dsh 升级后快速判断「是不是类名变了导致覆盖失效」。
     *
     * <p>只观察到第一次命中就断开，不在热路径上常驻。
     */
    private const val LAYOUT_CHECK = """
(function () {
  try {
    if (typeof MutationObserver === 'undefined' || typeof document === 'undefined') { return; }
    var obs = new MutationObserver(function () {
      var panel = document.querySelector('[class~="VOzbGW_panel"]');
      if (!panel) { return; }
      // 覆盖层只在手机竖屏生效：横屏本就该保持官方的左右布局，不算失效
      var portrait = window.matchMedia('(orientation: portrait) and (max-width: 600px)').matches;
      var dir = getComputedStyle(panel).flexDirection;
      window.__aptuidshSettingsLayout = portrait
        ? ((dir === 'column') ? 'applied' : 'stale')
        : 'n/a-landscape';
      obs.disconnect();
    });
    obs.observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) {
    // 自检失败不影响任何功能
  }
})();
"""

    /**
     * 页面错误捕获：把 WebView 里的 JS 报错收集到 `window.__aptuidshErrors`。
     *
     * <h3>为什么需要它</h3>
     * WebView 没有控制台可用，官方 Web UI 里任何一个客户端插件加载失败，用户只能看到一个
     * 语焉不详的界面（例如文件预览直接显示「文件资源服务不可用」），而**真正的原因
     * （哪个模块抛了什么）完全看不到** —— 本机也没有 logcat 可看。
     * 这里把 `error` / `unhandledrejection` / `console.error` / `console.warn` 都记下来，
     * 由 [WebUiActivity] 定时轮询并转发进环境控制台日志，用户「一键复制全部日志」即可回传。
     *
     * <p>只保留最近 60 条，且所有操作都吞异常 —— 诊断代码绝不能把页面搞坏。
     */
    private const val ERROR_CAPTURE = """
(function () {
  try {
    var g = window;
    if (g.__aptuidshErrors) { return; }
    var list = g.__aptuidshErrors = [];
    function push(kind, msg) {
      try {
        list.push('[' + kind + '] ' + String(msg).slice(0, 600));
        if (list.length > 60) { list.shift(); }
      } catch (e) { /* 记不上就算了 */ }
    }
    g.addEventListener('error', function (e) {
      push('error', ((e && e.message) || e) + ' @ ' + ((e && e.filename) || '?') + ':' + ((e && e.lineno) || 0));
    }, true);
    g.addEventListener('unhandledrejection', function (e) {
      var r = e && e.reason;
      push('reject', (r && (r.stack || r.message)) || r);
    });
    ['error', 'warn'].forEach(function (level) {
      var orig = console[level];
      if (typeof orig !== 'function') { return; }
      console[level] = function () {
        try {
          push('console.' + level, Array.prototype.map.call(arguments, function (a) {
            return (a && (a.stack || a.message)) || String(a);
          }).join(' '));
        } catch (e) { /* ignore */ }
        return orig.apply(console, arguments);
      };
    });
  } catch (e) { /* 诊断脚本绝不能影响页面 */ }
})();
"""

    /**
     * 「文件资源服务不可用」的取证脚本。
     *
     * 这条报错来自 `dsh-client-ui-sidebar-documentpreview`，触发条件是
     * `meta.status === "none"`，而 `status` 在 `dsh-client-resources` 里只有一种来源：
     * `providerOf(protocolOf(address)) === undefined`。也就是说只有两种可能：
     *   (a) `dsh-resource://file/…` 这个 provider 根本没注册 —— 于是 `file` 协议的
     *       `ctx.resources.register()` 从未发生（它的 `inject` 要求
     *       `resources` / `remote` / `remote.workspaceFiles` 三个服务齐全，
     *       少一个 cordis 就不会 apply，只会打一条 warn）；
     *   (b) `tab.contentId` 不是合法地址（`protocolOf` 返回 undefined）。
     *
     * 本脚本必须在 dsh 的 loader 队列脚本**之前**执行（inject 插在 `<head>` 之后第一个），
     * 用 setter 陷阱接住 `window.__ModuleLoader__`，从而能记录：哪些插件 bundle 真的
     * load 了、目标插件的 `apply` 有没有跑、跑的时候有没有抛。再定期把页面上出现的
     * `dsh-resource://` 地址与预览面板状态一起塞进 `__aptuidshErrors`，
     * 由 WebUiActivity 的 errorPoller 转写到环境控制台日志。
     *
     * 全程 try/catch，绝不影响页面本身。
     */
    private const val RESOURCE_PROBE = """
(function () {
  try {
    var g = window;
    if (g.__aptuidshProbe) { return; }
    var state = g.__aptuidshProbe = { modules: [], target: [] };
    var WANT = ['dsh-client-resources', 'dsh-api-remotes', 'dsh-api-workspace-files'];
    function short(id) { return String(id).replace('@deepseek-ai/', ''); }
    function wanted(id) {
      for (var i = 0; i < WANT.length; i++) { if (String(id).indexOf(WANT[i]) >= 0) { return true; } }
      return false;
    }
    function note(line) {
      try { (g.__aptuidshErrors || (g.__aptuidshErrors = [])).push('[probe] ' + line); } catch (e) { /* ignore */ }
    }
    var held;
    function hook(loader) {
      if (!loader || loader.__aptuidshHooked || typeof loader.load !== 'function') { return; }
      loader.__aptuidshHooked = true;
      var origLoad = loader.load;
      loader.load = function (registration) {
        try {
          if (registration && typeof registration.id === 'string') {
            var id = short(registration.id);
            state.modules.push(id);
            if (wanted(id) && typeof registration.factory === 'function') {
              var factory = registration.factory;
              registration = {
                id: registration.id,
                factory: function (require) {
                  var face;
                  try { face = factory(require); }
                  catch (e) {
                    state.target.push('factory-failed ' + id + ': ' + ((e && e.message) || e));
                    throw e;
                  }
                  try {
                    if (face && typeof face.apply === 'function' && !face.__aptuidshWrapped) {
                      var origApply = face.apply;
                      face.apply = function (ctx) {
                        var out;
                        try { out = origApply.call(this, ctx); }
                        catch (e) {
                          state.target.push('apply-failed ' + id + ': ' + ((e && e.message) || e));
                          throw e;
                        }
                        state.target.push('apply-ok ' + id);
                        return out;
                      };
                      face.__aptuidshWrapped = true;
                    }
                  } catch (e) { /* ignore */ }
                  return face;
                }
              };
            }
          }
        } catch (e) { /* ignore */ }
        return origLoad.call(this, registration);
      };
    }
    try {
      Object.defineProperty(g, '__ModuleLoader__', {
        configurable: true,
        get: function () { return held; },
        set: function (v) { held = v; try { hook(v); } catch (e) { /* ignore */ } }
      });
    } catch (e) { /* ignore */ }
    try { if (Object.getOwnPropertyDescriptor(g, '__ModuleLoader__') && g.__ModuleLoader__) { hook(g.__ModuleLoader__); } } catch (e) { /* ignore */ }

    function addresses() {
      var found = [], seen = {};
      try {
        var html = String(document.body && document.body.innerHTML || '');
        var m = html.match(/dsh-resource:\/\/[^"'\s<>\\)]{0,180}/g) || [];
        for (var i = 0; i < m.length && found.length < 6; i++) {
          var a = m[i];
          if (seen[a] !== true) { seen[a] = true; found.push(a); }
        }
      } catch (e) { /* ignore */ }
      return found;
    }
    function dump(tag) {
      try {
        var out = {
          tag: tag,
          modules: state.modules.length,
          res: state.modules.indexOf('dsh-client-resources') >= 0,
          rem: state.modules.indexOf('dsh-api-remotes') >= 0,
          wsf: state.modules.indexOf('dsh-api-workspace-files') >= 0,
          target: state.target.slice(-12),
          body: document.querySelectorAll('[data-textpreview-body]').length,
          stateNode: document.querySelectorAll('[data-textpreview-state]').length,
          unsupported: document.querySelectorAll('[data-textpreview-unsupported]').length,
          addr: addresses()
        };
        note(JSON.stringify(out));
      } catch (e) { note('dump-failed ' + ((e && e.message) || e)); }
    }
    function start() {
      var n = 0;
      dump('t0');
      var timer = setInterval(function () {
        n += 1;
        dump('t' + n);
        if (n >= 8) { clearInterval(timer); }
      }, 8000);
    }
    if (document.readyState === 'complete') { setTimeout(start, 4000); }
    else { g.addEventListener('load', function () { setTimeout(start, 4000); }); }
  } catch (e) { /* 诊断脚本绝不能影响页面 */ }
})();
"""

    private const val JS = """
// =====================================================================
// 第一部分：Iterator Helpers（Chromium 122）
// ---------------------------------------------------------------------
// dsh 自带的 `dsh-client-ui-sidebar-documentpreview` 内嵌了 PDF.js，
// 其中有一句特性检测：
//   "function" != typeof Iterator.prototype.join && (Iterator.prototype.join = …)
// `Iterator` 是 Chromium 122（2024-02）才引入的 JS 全局对象。
// 在旧内核上它是 undefined，于是 `Iterator.prototype` 直接抛
//   ReferenceError: Iterator is not defined
// 表现是官方界面一片报错：
//   Failed to load plugins
//   failed to import loader entry … (…documentpreview): Iterator is not defined
// 这里给出一个语义基本完整的 Iterator Helpers 实现（惰性求值），
// 而不只是让 `prototype` 存在——避免后续版本真的用到这些方法时再出问题。
// =====================================================================
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
// 第二部分：Chrome 116~122 的缺失 API（第一轮补齐，保留）
// ---------------------------------------------------------------------
//   Chrome 116 : AbortSignal.any / AbortSignal.timeout
//   Chrome 117 : Object.groupBy / Map.groupBy
//   Chrome 119 : Promise.withResolvers、Symbol.dispose（using 声明的编译产物依赖它）
//   Chrome 121 : Array.fromAsync
//   Chrome 122 : Set 集合运算方法
// 全部按「存在则跳过」的方式补齐，新内核上不产生任何副作用。
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

  // --- Symbol.dispose / Symbol.asyncDispose (Chrome 125 / 127) ---
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

// =====================================================================
// 第三部分：Chrome 123~147 的缺失 API（2026-09-24 升级 dsh 0.1.7-rc.1 后新补）
// ---------------------------------------------------------------------
// 这一批全部是**升级后才出现**的缺口，集中在懒加载的 PDF 预览链路
// （client.pdf.js 及其内嵌 worker）：
//   Chrome 126 : URL.parse                （URL.parse 失败返回 null，不抛异常）
//   Chrome 128 : Promise.try
//   Chrome 136 : RegExp.escape
//   Chrome 140 : Uint8Array 的 base64 / hex 六个方法
//   Chrome 147 : Math.sumPrecise
//   Chrome 132/144 : Response / Blob / Request 的 bytes()
// 同样「存在则跳过」，新内核零副作用。
// =====================================================================
(function () {
  var g = typeof globalThis !== 'undefined' ? globalThis : this;

  // --- Promise.try (Chrome 128) ---
  // 语义：立即调用 fn，把同步返回/同步抛出都收敛成一个 promise。
  if (typeof Promise !== 'undefined' && typeof Promise.try !== 'function') {
    Promise.try = function (fn) {
      var args = Array.prototype.slice.call(arguments, 1);
      return new Promise(function (resolve) {
        resolve(fn.apply(undefined, args));
      });
    };
  }

  // --- URL.parse (Chrome 126) ---
  // 与 new URL() 的唯一区别：解析失败返回 null 而不是抛 TypeError。
  if (typeof URL !== 'undefined' && typeof URL.parse !== 'function') {
    URL.parse = function (url, base) {
      try {
        return arguments.length > 1 ? new URL(url, base) : new URL(url);
      } catch (e) {
        return null;
      }
    };
  }

  // --- RegExp.escape (Chrome 136) ---
  // 规则逐码点对着 Chrome 原生实现推导出来的（第一/非第一位置分别对拍），共 6 条：
  //   1) 语法字符（脱字符/美元符/反斜杠/点/星/加/问/圆括号/方括号/花括号/竖线）与 '/'
  //   2) 首字符若是 ASCII 字母或数字 → \xHH（否则会被当成后向引用或量词）
  //   3) 其余 ASCII 单词字符（字母 / 数字 / 下划线）→ 原样
  //   4) \t \n \v \f \r → 短转义
  //   5) 其它 ASCII 标点与空白分隔符（含 NBSP）→ \xHH
  //   6) 其余一律原样 —— 非 ASCII 字符在正则里没有语法含义，原样恒安全
  // 字符集刻意写成 \xHH 字面量：Kotlin 原样字符串里美元符号是模板起始符，
  // 而且反引号/引号直接写进 JS 字符串也容易出错。
  if (typeof RegExp !== 'undefined' && typeof RegExp.escape !== 'function') {
    var SYNTAX = '\x5e\x24\x5c\x2e\x2a\x2b\x3f\x28\x29\x5b\x5d\x7b\x7d\x7c';
    var PUNCT = '\x20\x21\x22\x23\x25\x26\x27\x2c\x2d\x3a\x3b\x3c\x3d\x3e\x40\x60\x7e\xa0';
    function hexEsc(n) {
      if (n <= 255) {
        var h2 = n.toString(16);
        while (h2.length < 2) { h2 = '0' + h2; }
        return '\x5cx' + h2;
      }
      var u4 = n.toString(16);
      while (u4.length < 4) { u4 = '0' + u4; }
      return '\x5cu' + u4;
    }
    function isWordChar(n) {
      return (n >= 48 && n <= 57) || (n >= 65 && n <= 90) || (n >= 97 && n <= 122) || n === 95;
    }
    RegExp.escape = function (str) {
      var s = String(str), out = '';
      for (var i = 0; i < s.length; i++) {
        var c = s.charAt(i), n = c.charCodeAt(0);
        if (SYNTAX.indexOf(c) >= 0 || c === '/') { out += '\x5c' + c; continue; }
        if (i === 0 && n !== 95 && isWordChar(n)) { out += hexEsc(n); continue; }
        if (isWordChar(n)) { out += c; continue; }
        if (n === 9) { out += '\x5ct'; continue; }
        if (n === 10) { out += '\x5cn'; continue; }
        if (n === 11) { out += '\x5cv'; continue; }
        if (n === 12) { out += '\x5cf'; continue; }
        if (n === 13) { out += '\x5cr'; continue; }
        if (PUNCT.indexOf(c) >= 0) { out += hexEsc(n); continue; }
        out += c;
      }
      return out;
    };
  }

  // --- Math.sumPrecise (Chrome 147) ---
  // 规范要求「高精度求和」：用 Neumaier（Kahan–Babuška）补偿求和，
  // 比朴素累加更接近精确值。元素必须是 Number，否则抛 TypeError。
  if (typeof Math !== 'undefined' && typeof Math.sumPrecise !== 'function') {
    Math.sumPrecise = function (items) {
      if (items === null || items === undefined || typeof items[Symbol.iterator] !== 'function') {
        throw new TypeError('Math.sumPrecise: 参数必须是可迭代对象');
      }
      var sum = 0, comp = 0, it = items[Symbol.iterator](), step;
      while (!(step = it.next()).done) {
        var v = step.value;
        if (typeof v !== 'number') {
          throw new TypeError('Math.sumPrecise: 元素必须是 number');
        }
        var t = sum + v;
        if (Math.abs(sum) >= Math.abs(v)) {
          comp += (sum - t) + v;
        } else {
          comp += (v - t) + sum;
        }
        sum = t;
      }
      return sum + comp;
    };
  }

  // --- Uint8Array 的 base64 / hex（Chrome 140）---
  if (typeof Uint8Array !== 'undefined') {
    var B64_STD = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    var B64_URL = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
    var U8 = Uint8Array;

    function pickAlphabet(opts) {
      var a = opts ? opts.alphabet : undefined;
      if (a === undefined || a === 'base64') { return B64_STD; }
      if (a === 'base64url') { return B64_URL; }
      throw new TypeError('Uint8Array: 不支持的 alphabet: ' + a);
    }
    function revTable(alpha) {
      var rev = new Int16Array(256);
      for (var i = 0; i < 256; i++) { rev[i] = -1; }
      for (var j = 0; j < alpha.length; j++) { rev[alpha.charCodeAt(j)] = j; }
      return rev;
    }
    function decodeBase64(str, opts) {
      var rev = revTable(pickAlphabet(opts));
      // 规范允许并跳过 ASCII 空白
      var s = String(str).replace(/[\t\n\f\r ]+/g, '');
      var n = s.length, pad = 0;
      while (n > 0 && s.charCodeAt(n - 1) === 61) { n--; pad++; }
      if (pad > 2) { throw new SyntaxError('Uint8Array.fromBase64: 填充非法'); }
      var rem = n % 4;
      if (rem === 1) { throw new SyntaxError('Uint8Array.fromBase64: 长度非法'); }
      var outLen = Math.floor(n / 4) * 3 + (rem === 2 ? 1 : rem === 3 ? 2 : 0);
      var out = new U8(outLen), o = 0, i = 0;
      for (; i + 4 <= n; i += 4) {
        var c0 = rev[s.charCodeAt(i)], c1 = rev[s.charCodeAt(i + 1)];
        var c2 = rev[s.charCodeAt(i + 2)], c3 = rev[s.charCodeAt(i + 3)];
        if (c0 < 0 || c1 < 0 || c2 < 0 || c3 < 0) { throw new SyntaxError('Uint8Array.fromBase64: 含非法字符'); }
        out[o++] = (c0 << 2) | (c1 >> 4);
        out[o++] = ((c1 & 15) << 4) | (c2 >> 2);
        out[o++] = ((c2 & 3) << 6) | c3;
      }
      if (rem === 2) {
        var d0 = rev[s.charCodeAt(i)], d1 = rev[s.charCodeAt(i + 1)];
        if (d0 < 0 || d1 < 0) { throw new SyntaxError('Uint8Array.fromBase64: 含非法字符'); }
        out[o++] = (d0 << 2) | (d1 >> 4);
      } else if (rem === 3) {
        var e0 = rev[s.charCodeAt(i)], e1 = rev[s.charCodeAt(i + 1)], e2 = rev[s.charCodeAt(i + 2)];
        if (e0 < 0 || e1 < 0 || e2 < 0) { throw new SyntaxError('Uint8Array.fromBase64: 含非法字符'); }
        out[o++] = (e0 << 2) | (e1 >> 4);
        out[o++] = ((e1 & 15) << 4) | (e2 >> 2);
      }
      return out;
    }
    function encodeBase64(bytes, opts) {
      var alpha = pickAlphabet(opts);
      var omitPadding = !!(opts && opts.omitPadding);
      var n = bytes.length, out = '', i = 0;
      for (; i + 3 <= n; i += 3) {
        var b0 = bytes[i], b1 = bytes[i + 1], b2 = bytes[i + 2];
        out += alpha.charAt(b0 >> 2) + alpha.charAt(((b0 & 3) << 4) | (b1 >> 4))
             + alpha.charAt(((b1 & 15) << 2) | (b2 >> 6)) + alpha.charAt(b2 & 63);
      }
      var rest = n - i;
      if (rest === 1) {
        var c = bytes[i];
        out += alpha.charAt(c >> 2) + alpha.charAt((c & 3) << 4);
        if (!omitPadding) { out += '=='; }
      } else if (rest === 2) {
        var d0 = bytes[i], d1 = bytes[i + 1];
        out += alpha.charAt(d0 >> 2) + alpha.charAt(((d0 & 3) << 4) | (d1 >> 4))
             + alpha.charAt((d1 & 15) << 2);
        if (!omitPadding) { out += '='; }
      }
      return out;
    }
    function hexVal(c) {
      var n = c.charCodeAt(0);
      if (n >= 48 && n <= 57) { return n - 48; }
      if (n >= 97 && n <= 102) { return n - 87; }
      if (n >= 65 && n <= 70) { return n - 55; }
      return -1;
    }
    function decodeHex(str) {
      var s = String(str);
      if (s.length % 2 !== 0) { throw new SyntaxError('Uint8Array.fromHex: 长度为奇数'); }
      var out = new U8(s.length / 2);
      for (var i = 0; i < out.length; i++) {
        var hi = hexVal(s.charAt(i * 2)), lo = hexVal(s.charAt(i * 2 + 1));
        if (hi < 0 || lo < 0) { throw new SyntaxError('Uint8Array.fromHex: 含非法字符'); }
        out[i] = (hi << 4) | lo;
      }
      return out;
    }
    function encodeHex(bytes) {
      var HEX = '0123456789abcdef', out = '';
      for (var i = 0; i < bytes.length; i++) {
        var b = bytes[i];
        out += HEX.charAt(b >> 4) + HEX.charAt(b & 15);
      }
      return out;
    }

    if (typeof U8.fromBase64 !== 'function') {
      U8.fromBase64 = function (str, opts) { return decodeBase64(str, opts); };
    }
    if (typeof U8.fromHex !== 'function') {
      U8.fromHex = function (str) { return decodeHex(str); };
    }
    if (typeof U8.prototype.toBase64 !== 'function') {
      U8.prototype.toBase64 = function (opts) { return encodeBase64(this, opts); };
    }
    if (typeof U8.prototype.toHex !== 'function') {
      U8.prototype.toHex = function () { return encodeHex(this); };
    }
    if (typeof U8.prototype.setFromBase64 !== 'function') {
      U8.prototype.setFromBase64 = function (str, opts) {
        var src = decodeBase64(str, opts);
        var n = Math.min(src.length, this.length);
        this.set(src.subarray(0, n));
        return { read: String(str).length, written: n };
      };
    }
    if (typeof U8.prototype.setFromHex !== 'function') {
      U8.prototype.setFromHex = function (str) {
        var src = decodeHex(str);
        var n = Math.min(src.length, this.length);
        this.set(src.subarray(0, n));
        return { read: String(str).length, written: n };
      };
    }
  }

  // --- Blob / Response / Request 的 bytes()（Chrome 144 / 132）---
  // 返回 Promise<Uint8Array>；pdf.js 会直接对它再调 .toBase64()，
  // 所以必须返回真正的 Uint8Array（而不是 ArrayBuffer）。
  if (typeof Uint8Array !== 'undefined') {
    var withBytes = [];
    if (typeof Blob !== 'undefined') { withBytes.push(Blob); }
    if (typeof Response !== 'undefined') { withBytes.push(Response); }
    if (typeof Request !== 'undefined') { withBytes.push(Request); }
    for (var bi = 0; bi < withBytes.length; bi++) {
      (function (Ctor) {
        if (!Ctor || !Ctor.prototype || typeof Ctor.prototype.bytes === 'function') { return; }
        try {
          Object.defineProperty(Ctor.prototype, 'bytes', {
            value: function bytes() {
              return this.arrayBuffer().then(function (buf) { return new Uint8Array(buf); });
            },
            writable: true,
            configurable: true,
            enumerable: false
          });
        } catch (e) { /* 定义不上就跳过，不影响其它垫片 */ }
      })(withBytes[bi]);
    }
  }
})();
"""
}
