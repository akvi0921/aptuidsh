(function () {
  try {
    var g = window;
    if (g.__aptuidshBootSettle) { return; }
    var TARGET_ID = '@deepseek-ai/dsh-client-resources';
    var held = null, ctxRef = null, installed = false;

    /**
     * 数出「还没安定」的插件：state 2 = ACTIVE、3 = FAILED 之外都算没安定
     * （0 = PENDING，1 = LOADING，以及还没有 fiber 的条目）。
     * @returns 未安定的条目数；读不到名单时返回 -1。
     */
    function counts(loader) {
      if (!loader || typeof loader.entries !== 'function') { return -1; }
      try {
        var list = loader.entries(), pending = 0;
        for (var i = 0; i < list.length; i++) {
          var f = list[i] && list[i].fiber;
          if (!f || (f.state !== 2 && f.state !== 3)) { pending += 1; }
        }
        return pending;
      } catch (e) { return -1; }
    }
    /**
     * 等名单收敛（未安定数归零）。有界且可退让：
     * 只要 3 秒没有进展、或总时长超过 20 秒就放手，让 dsh 原本的启动检查照旧执行 ——
     * 这个补丁只负责「别检查得太早」，绝不把失败伪装成成功。
     */
    function settle(loader) {
      return new Promise(function (resolve) {
        var t0 = Date.now(), best = 1e9, progress = Date.now();
        var tick = setInterval(function () {
          var pending = counts(loader);
          if (pending < 0) { clearInterval(tick); resolve(); return; }
          if (pending < best) { best = pending; progress = Date.now(); }
          if (pending === 0) { clearInterval(tick); resolve(); return; }
          if (Date.now() - progress > 3000 || Date.now() - t0 > 20000) { clearInterval(tick); resolve(); }
        }, 50);
      });
    }
    /** 把 loader.await() 包成「原有语义 + 等名单收敛」。 */
    function patchAwait() {
      if (installed || !ctxRef || typeof ctxRef.get !== 'function') { return; }
      try {
        var loader = ctxRef.get('loader');
        if (!loader || typeof loader.await !== 'function' || loader.__aptuidshAwait) { return; }
        var orig = loader.await;
        loader.await = function () {
          var self = this, args = arguments, base;
          try { base = orig.apply(self, args); } catch (e) { throw e; }
          return Promise.resolve(base).then(function (value) {
            return settle(self).then(function () { return value; });
          });
        };
        loader.__aptuidshAwait = true;
        installed = true;
      } catch (e) { /* 改不了就完全退化成原样 */ }
    }
    /**
     * 只对 `dsh-client-resources` 这一个插件的 factory 包一层，目的仅是从 apply 的入参里
     * 取出 cordis ctx —— 它的 inject 只有 `slots`，是最早 apply 的插件之一，拿它取 ctx 最稳。
     * 注意：**就地改 factory，绝不新建/替换注册对象**（注册对象的同一性必须保住）。
     */
    function wrapFactory(registration) {
      try {
        if (!registration || registration.id !== TARGET_ID) { return; }
        if (typeof registration.factory !== 'function' || registration.__aptuidshFactory) { return; }
        var factory = registration.factory;
        var wrapper = function (require) {
          var face = factory(require);
          try {
            if (face && typeof face.apply === 'function' && !face.__aptuidshApply) {
              var origApply = face.apply;
              face.apply = function (ctx) {
                ctxRef = ctx;
                var out = origApply.call(this, ctx);
                patchAwait();
                return out;
              };
              face.__aptuidshApply = true;
            }
          } catch (e) { /* ignore */ }
          return face;
        };
        wrapper.__aptuidshFactory = true;
        Object.defineProperty(registration, 'factory', {
          value: wrapper, configurable: true, enumerable: true, writable: true
        });
        registration.__aptuidshFactory = true;
      } catch (e) { /* ignore */ }
    }
    function wrappedLoad(orig) {
      var fn = function (registration) { wrapFactory(registration); return orig.call(this, registration); };
      fn.__aptuidshWrapped = true;
      return fn;
    }
    /**
     * `window.__ModuleLoader__` 自始至终是**同一个对象**，但 dsh 的 `create()` 会把队列模式的
     * `load` 原地替换成「活体注册模式」的 `load`。所以这里挂一个访问器，替换发生时自动接住。
     */
    function installAccessor(loader) {
      try {
        var current = loader.load;
        if (typeof current !== 'function' || current.__aptuidshWrapped) { return; }
        Object.defineProperty(loader, 'load', {
          configurable: true,
          enumerable: true,
          get: function () { return current; },
          set: function (v) { current = typeof v === 'function' ? wrappedLoad(v) : v; }
        });
        current = wrappedLoad(current);
      } catch (e) { /* ignore */ }
    }
    function hook(loader) {
      if (!loader || loader.__aptuidshHooked) { return; }
      loader.__aptuidshHooked = true;
      held = loader;
      installAccessor(loader);
      try {
        var origCreate = loader.create;
        if (typeof origCreate === 'function') {
          loader.create = function (options) {
            var sys = origCreate.call(this, options);
            if (!(held.load && held.load.__aptuidshWrapped)) { installAccessor(held); }
            return sys;
          };
        }
      } catch (e) { /* ignore */ }
    }
    try {
      Object.defineProperty(g, '__ModuleLoader__', {
        configurable: true,
        get: function () { return held; },
        set: function (v) { held = v; try { hook(v); } catch (e) { /* ignore */ } }
      });
      if (g.__ModuleLoader__) { hook(g.__ModuleLoader__); }
    } catch (e) { /* ignore */ }
    /** 暴露给自测（`tools/polyfill-test/settle-test.mjs`）用的只读把手。 */
    g.__aptuidshBootSettle = { counts: counts, settle: settle, patched: function () { return installed; } };
  } catch (e) { /* 补丁绝不能影响页面本身 */ }
})();
