# WebView 兼容：两个根因与完整取证（1.3.4 → 1.4.0）

> **结论摘要**（细节见下文各节）
>
> 真机症状有两个，先是「工作区文件打不开，预览显示**文件资源服务不可用**」，
> 后来演变成**整页** `HARNESS / Failed to load plugins`。追到底，是两个互不相同的
> 根因，都出在这台机器只有 **Chrome 114** 的系统 WebView 上：
>
> | # | 根因 | 表现 | 修法 | 版本 |
> |---|---|---|---|---|
> | 1 | **WebView 不解析「非特殊 scheme」的 authority**：`new URL('dsh-resource://file/…').hostname` 为空、`pathname` 多出 `//file`。于是 dsh 的 `protocolOf()` 返回 undefined，`file` provider 永远匹配不上 → status 恒为 `none` | 文件预览永久显示「文件资源服务不可用」 | 垫片里给 `URL.prototype` 的 `hostname`/`pathname` 打补丁（特性探测 + 三条件判定，现代内核零副作用） | **1.3.9** |
> | 2 | **dsh web boot 的竞态**：内核只 `await loader.await()`（等模块加载完）就**一次性、无重试**地要求所有插件 active；而 `remote.*` 命名空间要等 22 次串行 `$mount` 才出现 | 整页停在 `Failed to load plugins`（34~35 条 pending） | 拿到 cordis `ctx` 后把 `loader.await()` 包成「等插件名单收敛」，有界可退让 | **1.3.7** |
>
> 过程里还踩了一次自己的坑：**取证脚本必须纯观测**。1.3.5 为了「缓解」而改变了 dsh 的行为，
> 反而制造了真实故障、差点把根因带偏（§14 有完整对比表）。


> 现象：官方 Web UI 里点开工作区文件，预览区显示 **「文件资源服务不可用」**。
> 本版（1.3.4）不猜测、不下结论，而是**先把证据取回来**：注入一段取证脚本，
> 把「插件有没有加载 / 有没有 apply / apply 有没有抛 / 页面上的资源地址长什么样」
> 全部写进环境控制台日志。

---

## 1. 这条报错的确切来源

先在 dsh 0.1.7-rc.1 的源码里把链路钉死。

**报错字符串**（`dsh-client-ui-sidebar-documentpreview/lib/client.js`）：

```js
if (state === void 0 || selected === void 0) return <div data-textpreview-state="loading">
  {meta.status === "none"
     ? <p>{t("resourceUnavailable")}</p>          // ← 「文件资源服务不可用」
     : <LoadingIndicator label={t("loading")} />}
</div>
```

`t("resourceUnavailable")` 的中文字典项就是 `resourceUnavailable: "文件资源服务不可用"`。

**`meta` 来自哪里**（同一文件）：

```js
const meta = useResource(tab.contentId);   // tab.contentId 形如 dsh-resource://file/session/<id>/<path>
```

`useResource` 由 `dsh-client-resources` 提供，`status` 只有 4 个取值：
`none / loading / live / failed`。

**`status === "none"` 的唯一来源**（`dsh-client-resources/lib/client.js`）：

```js
create(address) {
  const protocol = protocolOf(address);
  const store = createSnapshotStore(idle(this.providerOf(protocol) === void 0 ? "none" : "loading"));
  ...
}
providerOf(protocol) { return protocol === void 0 ? void 0 : this.providers.get(protocol); }
```

`protocolOf` 只认 `dsh-resource://`：

```js
if (parsed.protocol !== `dsh-resource:`) return void 0;
return parsed.hostname === "" ? void 0 : parsed.hostname.toLowerCase();   // "file"
```

### 结论：只有两种可能

| # | 条件 | 含义 |
|---|------|------|
| **A** | `providers` 里没有 `"file"` | 提供者压根没注册 |
| **B** | `protocolOf(tab.contentId)` 返回 `undefined` | 标签页给的地址不是合法 `dsh-resource://file/…` |

## 2. 谁负责注册 `file` 提供者

`dsh-api-workspace-files/lib/client.js`：

```js
const inject = ["resources", "remote", "remote.workspaceFiles"];

function apply(ctx) {
  const changes = new ChangeFeed(ctx.remote);
  const provider = createFileResourceProvider(ctx.remote, changes);   // protocol: "file"
  ctx.effect(() => {
    const release = ctx.resources.register(provider);
    ...
  }, "workspace-files: file resource provider");
}
```

三个服务**缺一个，cordis 就不会 apply**，只会打一条 warn —— 而看板上只会表现为
「文件资源服务不可用」。所以 A 类故障最常见的成因是 `remote.workspaceFiles` 不存在。

## 3. `remote.workspaceFiles` 是谁给的（关键发现）

`dsh-api-remotes/lib/client.js` 的 `apply` 是 **async**，并且**一次性挂载 22 个命名空间贡献**，
任何**一个**抛异常都会走 catch → 回滚全部 → **rethrow**：

```js
async function apply(ctx) {
  const disposers = [];
  try {
    for (const contribution of [ TYPERT_REMOTE$21, ..., TYPERT_REMOTE$17 ])
      disposers.push(await ctx.remote.$mount(contribution));
  } catch (error) {
    for (const dispose of disposers.reverse()) await dispose();
    throw error;                      // ← 全部命名空间一起消失
  }
  ...
}
```

这 22 个贡献里**确实包含** `@deepseek-ai/dsh-api-workspace-files`：

```
dsh-agent-preset-registry / dsh-api-account-controller / dsh-api-job-controller
dsh-api-session-controller / dsh-api-settings-controller / dsh-api-terminal-controller
dsh-api-workspace-controller / dsh-api-workspace-files ← 在
dsh-client-file-upload / dsh-client-ui-plugin-manager / dsh-command-feedback
dsh-commands / dsh-cordis-host-runner / dsh-goal / dsh-host-plugin-inventory
dsh-llm / dsh-message-feedback / dsh-office-to-pdf / dsh-permission-presets
dsh-plugin-manager / dsh-session-reference / dsh-subagent
```

而 `$mount` 的落地实现（`dsh-api-gateway/lib/client.js`）是**每个命名空间起一个 cordis fiber**：

```js
async $mount(contribution) { ... await this.enqueue(() => this.mountContribution(...)) }
async mountContribution(callerCtx, contribution) {
  const disposeRemote = callerCtx.typert.remotes.register(contribution);
  for (const [namespace, descriptors] of groups) installed.push(await this.installNamespace(namespace, descriptors));
}
async createNamespace(name, descriptors) {
  const fiber = this.ownerCtx.plugin({ name: remoteServiceKey(name), apply: (ctx) => { ... } });
  await fiber;                       // ← 旧内核上任何一处抛错，整条链就断在这里
}
```

**所以「A 类」在真机上的最可能机制是：** 22 个贡献中的某一个在 Chrome 114 下
`$mount` 抛错 → `dsh-api-remotes.apply` 整体回滚 → `remote.workspaceFiles` 不存在 →
`dsh-api-workspace-files` 不 apply → `file` 协议无 provider → 预览报此错。

## 4. 已经排除的可能

- **不是安卓侧的回归。** `git log --all -S "addJavascriptInterface"` 为空 ——
  本项目从未把文件桥接给 WebView；`WebUiActivity` 里只有 `evaluateJavascript`（205 行）。
  原生层现在只负责起环境、开 WebView，前端 100% 是官方页面。
- **不是宿主插件没开。** 在测试实例（3082）上用 `pluginInventory/list` 查过：
  `@deepseek-ai/dsh-api-workspace-files` 是 `"enabled": true, "fiberPhase": "active"`。
- **不是 bundle 没下发。** 三个 bundle 全部 HTTP 200（bundle3 = 5,646,926 B / 58 个插件），
  `node --check` 全过，bundle3 里 `dsh-api-workspace-files` 出现 20 次。
- **不是缺 API 垫片。** 垫片已覆盖 33 项：`Iterator` helpers（含 `join/concat/from`）、
  `Promise.try/withResolvers`、`Set` 的 7 个集合运算、`Object/Map.groupBy`、
  `Array.fromAsync`、`Math.sumPrecise`、`RegExp.escape`、`URL.parse`、
  `Uint8Array.toBase64/fromBase64/toHex/fromHex`、`AbortSignal.any/timeout`、
  `Symbol.dispose/asyncDispose`。对 `dsh-api-gateway`、`dsh-api-remotes`、
  `dsh-cordis-client-runner`（含 455 KB / 332 KB 两个大 bundle）做过
  Chrome>114 API 扫描，无缺口。

## 5. 1.3.4 做了什么：`RESOURCE_PROBE`

`WebPolyfill.kt` 新增 `RESOURCE_PROBE`，作为 `<script data-aptuidsh="resource-probe">`
注入。**注入点在第 6 个字节（紧跟 `<head>`）**，而 dsh 的 loader 队列脚本在第 235 个字节
—— 也就是说它**先于 `window.__ModuleLoader__` 的赋值执行**，所以能用 setter 陷阱接住 loader：

```js
Object.defineProperty(g, '__ModuleLoader__', {
  configurable: true,
  get: function () { return held; },
  set: function (v) { held = v; hook(v); }
});
```

`hook()` 包住 `loader.load`，对目标插件额外包一层 `factory` / `apply`，于是能记到：

- **`factory-failed <id>: <msg>`** —— 插件 bundle 执行期抛错
- **`apply-failed <id>: <msg>`** —— 插件 apply 抛错（A 类故障会在这里露出原话）
- **`apply-ok <id>`** —— 确实跑完了

再每 8 秒（共 9 次）把现场快照写进 `__aptuidshErrors`：

```js
{ tag, modules, res, rem, wsf, target, body, stateNode, unsupported, addr }
```

- `res` / `rem` / `wsf` —— 三个关键插件**是否真的 load 了**
  （区分「bundle 没下发」还是「下发了没生效」）
- `target` —— 最近 12 条 `apply-ok` / `apply-failed` / `factory-failed`
- `addr` —— **从页面 DOM 里正则抓出来的真实 `dsh-resource://…` 地址**（最多 6 条）
  → 直接判定 B 类：地址是不是合法 `dsh-resource://file/…`
- `body` / `stateNode` / `unsupported` —— 预览面板当前处于哪种状态

这些行由 `WebUiActivity` 已有的 `errorPoller`（每 2 秒）捞出来，
以 `[web] ` 前缀写进环境控制台日志。

全程 try/catch 包裹，**任何异常都不会影响页面本身**；`RESOURCE_PROBE` 的源码
单独抽出来跑过 `node --check`。

## 6. 怎么取证

1. 装 1.3.4（`Downloads/APTUIDSH-1.3.4.apk`）。
2. 打开 APP → 进官方 Web UI。
3. 点开工作区里一个文件，等到出现「文件资源服务不可用」。
4. 再等 10 秒（让探针至少跑一轮 `t0`/`t1`）。
5. 回首页 → **环境控制台** → 把日志里所有 `[web] ` 开头的行发我。

重点看两行：

- `[web] [probe] {"tag":"t1", ...}` —— 完整现场
- `[web] [probe] apply-failed dsh-api-remotes: …` 或 `apply-failed dsh-api-workspace-files: …`
  —— 若有，**这就是根因原话**

## 7. 本版改动清单

| 文件 | 改动 |
|------|------|
| `app/src/main/java/com/aptuidsh/kui/WebPolyfill.kt` | 新增 `RESOURCE_PROBE` 常量 + 注入一行 |
| `app/build.gradle` | `versionCode 22` / `versionName "1.3.4"` |

自检：`bash tools/polyfill-test/run.sh` → **通过 109 / 失败 0**。

---

# 【真机实测】1.3.4 的探针报了假数据 —— 以及 1.3.5 的修正

## 8. 1.3.4 真机日志（用户回传）

```
00:39:55 INFO  WebView UA: … Chrome/114.0.5735.196 …
00:39:55 INFO  已向官方 Web UI 注入兼容性垫片（原文档 33582 字节，2 跳）
00:39:58 INFO  页面内 typeof Iterator = "function"
00:40:04 WARN  [web] [probe] {"tag":"t0","modules":1,"res":false,"rem":false,"wsf":false,"target":[],"body":0,"stateNode":0,"unsupported":0,"addr":[]}
00:40:12 WARN  [web] [probe] {"tag":"t1","modules":1,…,"stateNode":1,…}
00:40:20 WARN  [web] [probe] {"tag":"t2","modules":1,…,"stateNode":1,…}
00:40:28 WARN  [web] [probe] {"tag":"t3","modules":1,…,"stateNode":1,…}
```

### 两个结论

**① 探针本身失效了：`modules` 恒为 1 是假象。**

`dsh-client-modules` 的启动协议里写得很清楚：

> `window.__ModuleLoader__` is the **same facade** left in live-registration mode.

也就是说 `window.__ModuleLoader__` 自始至终是**同一个对象**，只接一次 `load` 是不够的 ——
`create()` 会调用 `createClientModuleSystem(this, …)`，把队列模式的
`load(registration){pendingQueue.push(...)}` **原地替换**成「活体注册模式」的 `load`。
我在 `<head>` 抢跑接住的那个包装，在 `create()` 之后就被无声丢弃了。
所以 `modules` 只数到 `client-modules` 自己那一次注册，`res/rem/wsf` 全是 false，
`target` 全空 —— **全是假数据，不能用来定案。**

**② 但有一条真信息可用：`stateNode` 从 0 变 1，且全程没有任何 `console.error/warn`。**

页面**没有任何 JS 报错**，而预览面板的 `[data-textpreview-state]` 元素是**渲染出来的**。
这一点很关键：该元素由 `dsh-client-ui-sidebar-documentpreview` 渲染，它通过 slot props
拿到 `useResource`，而 `useResource` 正是 `dsh-client-resources` 在
`ctx.slots.provideRoot({ keyedHooks: { resource: (address) => resources.source(address) } })`
里注入的。

于是可以**反推**：`useResource` 能用 ⇒ `dsh-client-resources.apply` 跑过了 ⇒
`resources` 服务存在。所以：

- 排除「`resources` 服务缺失」；
- `meta.status === "none"` 只剩两种可能：`file` provider 没注册，或 `contentId` 不是合法地址。

## 9. 1.3.5 的两处修正

### (a) 探针：不再数数，直接读注册表 —— 拿 ground truth

包住目标插件 `apply` 的时候顺手把它的 cordis `ctx` 偷出来，于是能直接读：

| 字段 | 来源 | 意义 |
|------|------|------|
| `providers` | `ctx.resources.providers` 的 keys | **`file` 到底注册了没有**（一击定 A/B） |
| `records` | `ctx.resources.records` 的 keys | **预览请求的真实地址**（B 类的铁证） |
| `rw` / `remote` / `slots` | `ctx.get(...)` | 三个 inject 齐不齐 |
| `panel.text` / `panel.attr` / `panel.path` | DOM | 面板上到底是哪句话、哪个文件 |
| `module-count` + `ids[...]` | loader | 完整插件名单，分块输出（每行 8 个） |

同时修掉 ①：包住 `create()`，**在它返回后立刻重新包 `load`**，另加 50ms 轮询兜底 20 秒。

### (b) 实验性缓解：让「22 个命名空间一损俱损」不再致命

`dsh-api-remotes.apply` 的结构是：

```js
try { for (const c of [22 个贡献]) disposers.push(await ctx.remote.$mount(c)); }
catch (error) { for (const d of disposers.reverse()) await d(); throw error; }   // ← 全部回滚
```

任意一个命名空间挂载失败 ⇒ 其余（含 `remote.workspaceFiles`）全被回滚 ⇒
`dsh-api-workspace-files` 不 apply ⇒ `file` provider 不存在 ⇒ 正是本 bug 的现象。

1.3.5 把那个挂载方法包成「失败也返回一个空 disposer」，于是**其余命名空间照常装上**，
并在 `target` 里记下 `mount-failed <包名>: <原话>`。
**这既是取证，也可能是直接修复** —— 只有当失败的那个恰好是 `workspaceFiles` 本身时才会无效。

> 方法名用 `String.fromCharCode(36) + 'mount'` 拼出来：Kotlin 原样字符串里写
> 「美元 + 字母」会被当成模板起始符，直接编译不过。这个坑已经踩过一次，
> 现在 `tools/polyfill-test` 里有专门断言守着。

## 10. 顺手修掉的构建陷阱

`tools/build-apk.sh` 之前**不检查 gradle 退出码**：`script -q -f -c "gradle …"` 之后
无条件打印「构建完成」，于是这一次真的出现了「BUILD FAILED（Kotlin 编译错误）
却报构建成功」的事故 —— 差一点就把上一版 APK 当新版交付。
现在显式检查 `$?`，失败即 `exit`，并在提示里指明「上面 BUILD FAILED 的报错才是真相」。

## 11. 本版改动清单

| 文件 | 改动 |
|------|------|
| `app/src/main/java/com/aptuidsh/kui/WebPolyfill.kt` | 探针重写：`create()` 后再接 `load`、读 `providers`/`records`、偷 `ctx`、`patchMount()` 缓解 |
| `app/build.gradle` | `versionCode 23` / `versionName "1.3.5"` |
| `tools/polyfill-test/check.mjs` | +4 道断言：探针无美元符、语法可解析、`create()` 后再接 `load`、读注册表、mount 缓解在位 |
| `tools/build-apk.sh` | 显式检查 gradle 退出码（修「失败仍报成功」） |

自检：`bash tools/polyfill-test/run.sh` → **通过 113 / 失败 0**。
产物：`aapt2 dump badging` → `versionCode='23' versionName='1.3.5'`。

---

# 【真机实测二】1.3.5 把「取证」变成了「制造故障」，以及真正的根因

## 12. 1.3.5 真机日志（用户回传）

```
00:55:25 WARN  [web] [console.error] Error: web boot: 35 entries did not activate
00:55:25 WARN  [web] @deepseek-ai/dsh-api-workspace-files: pending (waiting for service: remote.workspaceFiles)
00:55:25 WARN  [web] @deepseek-ai/dsh-client-ui-sidebar-documentpreview: pending (waiting for services: sidebarRightTabs, remote.workspaceFiles)
…（35 条，日志到 @deepseek-ai/dsh-client-ui-sidebar-right: pe 被截断）

00:55:41 WARN  [web] [probe] {"tag":"t1","loadSeen":63,"ids":63,"res":true,"rem":true,"wsf":true,
  "target":["apply-ok dsh-client-resources","mount-patched","apply-ok dsh-api-remotes","apply-ok dsh-api-workspace-files"],
  "providers":["subagentchat","plan","file"],"records":[],
  "rw":"object","remote":"object","slots":"object",
  "panel":{"text":null,"attr":null,"path":null,"body":false},"addr":[]}
```

页面上是 **"HARNESS / Failed to load plugins"** 遮罩，WebUI 完全不可用。

## 13. 三个硬结论

**① 探针这次跑对了。** `loadSeen: 63`（全部插件）、`res/rem/wsf` 全 true、
`target` 里 `apply-ok dsh-api-workspace-files`、`providers: ["subagentchat","plan","file"]`
—— **`file` provider 确实注册成功了**，`rw: "object"` 说明 `remote.workspaceFiles` 也存在。

**② 但在 boot 检查那一刻（页面启动后约 2.7 秒），35 个插件还是 pending。**
也就是说：资源链路本身是好的，只是**激活得太晚**。

**③ 那条 boot 检查是「一次性、无重试」的，而且按构造就是竞态。**
从 shell bundle（`assets/index-3dwUbT.js`）里挖出来的内核：

```js
async function tE(e) {
  const { ctx: n, manifest: o, onEntryState: s } = e;
  await n.plugin(Dd);
  const a = n.loader;
  await e.modules.entries.start(a, o);     // 只 await「模块加载」，不等 fiber 激活
  await a.await();
  nE(n, e.modules);                        // ← 立刻要求每个 entry 都是 active
}
function nE(e, n) {
  for (const s of e.loader.entries()) {
    const u = a7[s.fiber.state];
    if (u !== "active") { … o.push(`${a}: pending (waiting for services: …)`) }
  }
  if (o.length > 0) throw new Error(`web boot: ${o.length} entries did not activate\n…`)
}
```

`entries.start` 内部是 `await Promise.all(desired.map(create))` + `await loader.await()` ——
**`loader.await()` 等的是模块加载完成，不是插件 fiber 激活完成**。而 `remote.*` 命名空间
要等 `dsh-api-remotes.apply` **串行**跑完 22 个 `await ctx.remote.$mount(...)` 才会出现。
于是「模块都下好了」和「服务全部就位」之间存在一个窗口；内核恰好在这个窗口里做了
**一次性、无重试**的全量检查，检查不过就 `throw`，boot 遮罩永久显示失败。

Chrome 114 的 WebView 上这个窗口更大 —— **这就是 `文件资源服务不可用` 的真正根因**：
roster 没激活完时，预览面板拿不到 `file` provider，`meta.status` 就是 `"none"`。
换句话说，之前那个「文件打不开」和这次的「boot 失败」**是同一个竞态的两个轻重档**。

## 14. 1.3.5 做错了什么（必须记下来）

我在 1.3.5 里为了「缓解 22 个命名空间一损俱损」而包住了 `remote` 的挂载方法，
并且把注册对象换成了 `{id, factory}` 新对象。**真机结果是对比出来的**：

| 版本 | 探针行为 | 真机结果 |
|------|----------|----------|
| 1.3.4 | 只接一次 `load`（实际接空） | boot 干净，无 `web boot` 报错 |
| 1.3.5 | 包 `create`+`load`+`apply`+`$mount`，换注册对象 | **boot 35 条 pending，整页失败** |

而日志同时证明 `mount-patched` 生效、**没有任何 `mount-failed`** —— 也就是说那 22 个
挂载本来就全部成功，我那个「缓解」**根本没起作用**，却带来了一次真实故障。

**教训：取证脚本必须是纯观测的。** 一旦它改变被测系统的行为，你就再也分不清
「报出来的问题」和「自己造出来的问题」。1.3.6 因此全面回退到只读：

| 1.3.5 的做法 | 1.3.6 改为 |
|--------------|------------|
| 返回 `{id, factory}` 新注册对象 | **就地** `defineProperty(registration,'factory')`，保持同一性与全部字段 |
| 包住 `remote` 的挂载方法（行为改变） | **整段删除** |
| 50ms 轮询重包 `load` | 给 `load` 挂**访问器**，被替换时自动接住；不再轮询 |
| — | 新增 `state.since` 时间线：量出各服务「第一次出现」的时刻 |
| — | 新增 boot 遮罩原文抓取（`[data-dsh-boot]`，不受日志截断影响） |
| 控制台单条截断 600 字（把 35 条清单截成半句） | 放宽到 **4000 字** |

## 15. 1.3.6 要回答的问题

装上后打开 WebUI，环境控制台里看这几样：

1. **`boot`** —— boot 遮罩原文。是 `Loading plugins…`（成功）还是失败清单（失败）。
2. **`since`** —— `{registry, remoteSession, remoteWorkspaceFiles, wsfApplied}` 各自
   第一次出现的毫秒数。**这就是竞态窗口的宽度**：如果 `remoteWorkspaceFiles` 出现在
   2300ms 而检查在 2700ms 抛出，那就实锤。
3. **`providers` / `records` / `addrs`** —— 点开文件后再看：`addrs` 会打印
   `resources.source(address)` 收到的**真实地址**，这是判定「地址不合法」那条分支的铁证。
4. **`panel.text`** —— 面板上到底是「文件资源服务不可用」还是「正在读取…」。

## 16. 本版改动清单（1.3.6）

| 文件 | 改动 |
|------|------|
| `app/src/main/java/com/aptuidsh/kui/WebPolyfill.kt` | 探针全面改为纯观测；删除 `patchMount`；注册对象就地改；`load` 访问器；`since` 时间线；boot 遮罩抓取；控制台截断 600→4000 |
| `app/build.gradle` | `versionCode 24` / `versionName "1.3.6"` |
| `tools/polyfill-test/check.mjs` | 断言改为守护「探针不得改变 dsh 行为」 |

自检：`bash tools/polyfill-test/run.sh` → **通过 116 / 失败 0**。

---

# 【真机实测三】1.3.6 纯观测版仍然失败 —— 根因确定，1.3.7 出手

## 17. 1.3.6 真机结果：探针无罪

1.3.6 的探针已经是**纯观测**（不换注册对象、不包挂载方法、只挂一个 `load` 访问器），
真机仍然报 `web boot: 34 entries did not activate`。

**这条否定结果很重要**：1.3.5 的「加重」和 1.3.6 的「减重」结果一样，
说明这个 boot 失败**不是探针造成的**——1.3.4 那次 boot 干净是**赢了这个竞态**，
而不是因为探针动作少。之前我怀疑自己的探针，方向错了（虽然 1.3.5 的多余改动仍然该删）。

## 18. 根因链（已闭环）

从 shell bundle 挖出的内核 + `dsh-client-modules` 的 entry 机制，拼起来是这样：

```js
// shell（web boot 内核）
await e.modules.entries.start(a, o);
for (const f of a.entries()) if (f.fiber === void 0) s?.(f.options.name, "failed");
await a.await();
nE(n, e.modules);                    // 一次性、无重试

function nE(e, n) {
  for (const s of e.loader.entries()) {
    const u = a7[s.fiber.state];
    if (u !== "active") o.push(`${a}: pending (waiting for services: …)`);
  }
  if (o.length > 0) throw new Error(`web boot: ${o.length} entries did not activate\n…`);
}
```

```js
// dsh-client-modules：entries.start 内部
await loader.await();
for (const [id, entry] of this.managed) {
  if (entry.fiber?.state === ACTIVE) continue;
  await entry.fiber.await();                                   // ← 对 pending 的 fiber 也会返回
  failures.push({ id, message: `client-modules: ${id} is waiting for activation` });
}
```

而 `remote.*` 这些命名空间，来自 `dsh-api-remotes.apply` 里**串行**的 22 次
`await ctx.remote.$mount(contribution)`：

```js
async function apply(ctx) {
  const disposers = [];
  try { for (const contribution of [22 个]) disposers.push(await ctx.remote.$mount(contribution)); }
  catch (error) { for (const dispose of disposers.reverse()) await dispose(); throw error; }
}
```

于是链条是：
**模块全部加载完（快） → 22 个命名空间串行挂载（慢） → `sessions`/`remote.*` 才存在 →
`dsh-client-ui-*` 才可能 active**。
内核恰好在「模块加载完」之后立刻做一次性全量检查 —— 撞上窗口就 throw，
boot 遮罩永久停在 Failed to load plugins。

`remote.session` 缺 → `dsh-api-session-controller` pending → `sessions` 缺 →
34 个 `dsh-client-ui-*` 全部 pending。这正好解释了那 34 条清单的形状。

**同一个竞态的轻档**：窗口内预览面板拿不到 `file` provider，`meta.status` 就是 `"none"`，
于是显示「文件资源服务不可用」。所以「文件打不开」和「整页 boot 失败」是**同一个 bug**。

## 19. 1.3.7 的修复：让 `loader.await()` 等到名单真正收敛

这是本仓库**唯一一处有意改变 dsh 行为**的补丁，所以做了三件事：有界、可退让、可单测。

```js
function patchLoaderAwait(ctx) {
  var loader = ctx.get('loader');                       // shell 里 reflect.provide("loader", this)
  if (!loader || typeof loader.await !== 'function' || loader.__aptuidshAwait) { return; }
  var orig = loader.await;
  loader.await = function () {
    var base = orig.apply(this, arguments);
    state.awaited += 1;
    return Promise.resolve(base).then(function (v) { return settle(loader).then(function () { return v; }); });
  };
  loader.__aptuidshAwait = true;
}
```

`settle()` 每 50ms 读一次 entry 名单（`loader.entries()` → `fiber.state`，
`0=PENDING 1=LOADING 2=ACTIVE 3=FAILED`），直到：

- **`pending === 0`** → 收敛，正常放行（记 `roster-settled+<耗时>ms`）；
- **无进展超过 3 秒**，或**总时长超过 20 秒** → 放手，让原本的检查照旧跑，
  并把 `roster-stuck pending=… active=… failed=…` 与**缺失服务计数**记下来
  （`missingServices()`：谁还没 active、卡在哪些服务上、各有多少个插件在等）。

关键点：**它绝不会把「失败」伪装成「成功」**——卡住就退让，原检查照常执行；
它只是把「检查得太早」这个缺陷补上。

### 这个补丁有真实计时单测（`tools/polyfill-test/settle-test.mjs`）

用真实时钟（不 mock 计时器，避免测出假绿）在 Node 里配 window/document 假体跑：

```
ok  counts() 认得全 active
ok  已收敛时 settle 立刻返回(<300ms)
ok  竞态场景等到收敛(>=400ms) 且不超时(<2000ms)  [实测 408ms]
ok  卡死时约 3 秒放手(2500~6000ms)  [实测 3076ms]
ok  放手后记下 roster-stuck
ok  拿不到 loader 时 counts() 返回 null 而不抛
--- settle 单测：通过 6 / 失败 0
```

已接入 `tools/polyfill-test/run.sh`，成为交付前门禁的一部分
（`node check.mjs` + `node settle-test.mjs`，后者从 Kotlin 里抽真实源码）。

## 20. 1.3.7 装完后看什么

| 日志字段 | 含义 | 期望 |
|----------|------|------|
| `target` 含 `loader-await-patched` | 补丁装上去了 | 必须有 |
| `roster-settled+NNNms` | 等了多久才收敛 | 几百 ms ~ 几秒 |
| `boot` | boot 遮罩原文 | 应为 `HARNESS Loading plugins…` |
| `roster-stuck …` + `missing` | 卡住时缺哪些服务 | 若出现，`missing` 直接指向下一个要修的点 |

如果 `roster-settled` 出现且 `boot` 变成 Loading —— 修复成功；
如果出现 `roster-stuck`，`missing` 会告诉我到底哪个 provider 永远不来，
那就是下一个（也更靠下的）修点。

## 21. 版本总览

| 版本 | 关键改动 | 真机结果 |
|------|----------|----------|
| 1.3.4 | 第一版探针（只接一次 `load`，接空） | boot 干净，文件预览不可用 |
| 1.3.5 | 探针加「mount 缓解」+ 换注册对象 | boot 35 条 pending，整页失败 |
| 1.3.6 | 探针回退纯观测 + `since` 时间线 + 截断 600→4000 | **仍 34 条 pending** ⇒ 探针无罪，根因是竞态 |
| **1.3.7** | **`loader.await()` 等到名单收敛（有界、可退让、有计时单测）** | 待验证 |

---

# 【真机实测四】1.3.7 修好了 boot；「文件打不开」收窄到最后一个矛盾

## 22. boot 竞态：已修复（真机确认）

```
01:10:09 WARN [web] [probe] {"tag":"t0","at":3204,"load":63,"res":true,"rem":true,"wsf":true,
  "target":["apply dsh-client-resources","loader-await-patched","apply dsh-api-remotes",
            "apply dsh-api-workspace-files","roster-settled+194ms","roster-settled+193ms",
            "roster-settled+58ms","roster-settled+228ms"],
  "boot":null, ...}
```

- `loader-await-patched` 在位，`roster-settled` 四次，耗时 **58~228ms**；
- **`boot: null`** —— 页面上没有 boot 遮罩，`HARNESS / Failed to load plugins` **消失**；
- 整份日志里**再没有** `web boot: N entries did not activate`。

即：让 `loader.await()` 等到名单收敛，检查就落在窗口之后了；而且收敛本身只要 ~200ms，
**对正常启动几乎没有额外开销**。整页不可用的问题解决。

## 23. 「文件打不开」收窄到一个矛盾

同一条日志给出了关键数据：

```
providers: ["subagentchat","plan","file"]          ← file provider 已注册
records:   ["sidebar://files",
            "dsh-resource://file/session/session-932f98d1-ad85-4d50-9638-fee4aa9cfa6b/make-test-image.js"]
panel:     {"text":"文件资源服务不可用","path":null,"body":false}
```

**两条旧分支都死了**：

- 「provider 没注册」→ 错。`providers` 里明明有 `file`。
- 「地址不合法」→ 错。地址是标准的 `dsh-resource://file/session/<sid>/<path>`。

顺带查清了 `sidebar://files` / `sidebar://guide` 的来历：它们是
`dsh-client-ui-sidebar-right` 里 `pageAddress(kind)` 造的**页面标签记账地址**
（`sidebar://<kind>`），本来就没有 provider，属于噪音，已从记录打印里过滤掉。

于是剩下唯一的矛盾：**provider 在、地址对，`meta.status` 却是 `"none"`。**

按 `dsh-client-resources` 的代码，`none` 只有一个来源：

```js
create(address) {
  const protocol = protocolOf(address);                    // ← 这里
  const store = createSnapshotStore(idle(this.providerOf(protocol) === void 0 ? "none" : "loading"));
}
function protocolOf(address) {
  parsed = new URL(address);
  if (parsed.protocol !== `dsh-resource:`) return void 0;
  return parsed.hostname === "" ? void 0 : parsed.hostname.toLowerCase();   // ← 或这里
}
```

只要 `protocolOf(address)` 返回 `undefined`，记录就**永久卡在 `"none"`**，
而且 `register()` 里那句补救（`for (const record of this.recordsOf(protocol)) this.attach(record)`）
也**永远匹配不到它**（因为它 `record.protocol` 也是 undefined）——
完美解释「provider 明明在注册表里，面板却一口咬定不可用」。

## 24. 1.3.8：把这条记录的真身打出来

新增 `recs()`：逐条打印注册表记录（跳过 `sidebar://*` 噪音），每条给出

| 字段 | 含义 |
|------|------|
| `a` | 地址末 64 字符 |
| `proto` | **注册表自己算出来的** protocol（`UNDEFINED` 即 `protocolOf` 没认出来） |
| `st` | 当前 status：`none` / `loading` / `live` / `failed` |
| `h` | holders（有几个订阅者） |
| `u` | 探针**现场用 `new URL()` 再解一遍**：`protocol\|hostname\|pathLen` |
| `f` | 失败原因（status=failed 时） |

另外给 `settle` 加了统计（`calls` / `maxTotal` / `maxPending` / `waits`），
用来确认那个 boot 补丁**到底有没有真的等过东西**。

### 三种读数对应三种修法

| 读数 | 结论 | 修法 |
|------|------|------|
| `proto: "UNDEFINED"` | 这个 WebView 的 `new URL()` 对非特殊 scheme 的 hostname 解析异常 | 在垫片里给 `URL` 打补丁（只针对非特殊 scheme 的 hostname） |
| `proto: "file"` 且 `st: "none"` | 记录先于 provider 建立，而 `attach()` 的补救没生效 | 在 provider 注册后主动把已有记录 `attach` 一遍 |
| `st: "live"` | 文件其实读得到，报错的是**另一个面板/标签** | 转向排查是哪个 tab 在使用 `sidebar://*` 这类地址渲染 TextPreview |

---

# 【真机实测五】根因确认：Chrome 114 的 WebView 不解析非特殊 scheme 的 authority

## 25. 决定性读数

1.3.8 打出的 `recs` 里，那条文件记录的真身是：

```json
{"a":"/session-932f98d1-ad85-4d50-9638-fee4aa9cfa6b/make-test-image.js",
 "proto":"UNDEFINED","st":"none","h":3,
 "u":"dsh-resource:||78","f":null}
```

`u` 是探针**现场用 `new URL()` 再解一遍**的结果，格式为 `protocol|hostname|pathname长度`：

| | protocol | hostname | pathname 长度 |
|---|---|---|---|
| **真机 Chrome/114.0.5735.196 WebView** | `dsh-resource:` | **（空）** | **78** |
| Node（规范实现） | `dsh-resource:` | `file` | 72 |

78 恰好比 72 多 6 —— 正好是 `//file` 那 6 个字符。也就是说：

> **这个内核根本没有解析 non-special scheme 的 authority，把 `//file` 整段当成了路径的一部分。**

完整串是 `dsh-resource://file/session/session-932f98d1-…/make-test-image.js`，
规范解析应得 `hostname='file'`、`pathname='/session/…'`；真机给出 `hostname=''`、`pathname='//file/session/…'`。

## 26. 为什么这一条就足以让文件永远打不开

`dsh-client-resources` 靠 hostname 判断地址归哪个 provider 管：

```js
function protocolOf(address) {
  parsed = new URL(address);
  if (parsed.protocol !== `dsh-resource:`) return void 0;
  return parsed.hostname === "" ? void 0 : parsed.hostname.toLowerCase();   // ← 这里返回 undefined
}
```

于是链条是（每一环都由读数证实）：

1. `protocolOf(文件地址)` → `undefined`（因为 hostname 为空）；
2. `record.protocol` 也就是 `undefined`（`recs.proto` 读数正是 `UNDEFINED`）；
3. `providerOf(undefined)` → `undefined` → 记录初始 status 就是 `"none"`（`recs.st` 读数正是 `none`）；
4. provider 注册时那句补救 `for (const record of this.recordsOf(protocol)) this.attach(record)`
   **永远匹配不到它** —— 因为它自己的 `record.protocol` 也是 `undefined`；
5. 于是 `meta.status` 永远是 `"none"`，预览**永久**显示「文件资源服务不可用」。

同时排除了所有其他假设：

- **不是** provider 没注册 —— `providers: ["subagentchat","plan","file"]`；
- **不是**地址字符串不合法 —— 它是标准的 `dsh-resource://file/session/<sid>/<path>`；
- **不是**记录先于 provider 建立 —— 那种情况会被 `attach()` 补救，而这里连补救的匹配都进不去；
- **不是**我的垫片或探针 —— 我已核查垫片只**新增** `URL.parse` 静态方法，
  从不触碰 `URL.prototype`；也 grep 过全部 63 个客户端 bundle，没有任何一处替换 `window.URL`。

## 27. 1.3.9 的修复

在垫片里补上 authority 解析，**先特性探测、只补被误判的那一种情形**：

```js
var compliant = new U('dsh-probe://host/path').hostname === 'host';
if (compliant) { return; }        // 内核正确：一个字都不改
```

只在**三个条件同时成立**时才判定为「authority 被当成了路径」，才动手：

1. 原生 `hostname` 为空；
2. 原生 `pathname` 以 `//` 开头；
3. 串里确实写了 `scheme://authority`。

命中就补两条只读属性：`hostname` 从 href 里抠出主机名（会剥掉 userinfo 与端口、
支持 IPv6 字面量），`pathname` 去掉开头那段 `//host`。

**这样为什么不会误伤**：`http://a//b` 这种特殊 scheme 的双斜杠路径，其 `hostname` 非空，
条件 1 直接不成立，`pathname` 一个字节都不动；`foo:/bar`（没有 `//`）条件 3 不成立；
`data:text/plain,hello` 同理。

### 差分测试（`tools/polyfill-test/url-authority-test.mjs`）

本机 Node 的 `URL` 是**规范实现**，直接测只会得到「Node 本来就对」的假绿。
所以测试**先把 `URL` 换成复刻该缺口的假体**，再装垫片：

```
--- A. 模拟 Chrome 114 旧内核（authority 不解析）+ 垫片 ---
  ok   假体确实复刻了缺口：hostname 为空
  ok   假体确实复刻了缺口：pathname 多出 //file
  ok   假体下 dsh 的 protocolOf 判定为 undefined（即「文件资源服务不可用」）   ← 先复现故障
  ok   垫片后 hostname 恢复为 file
  ok   垫片后 pathname 恢复为 /session/…
  ok   垫片后 dsh 的 protocolOf 判定为 file（修复生效）
  ok   带端口的 authority 也被正确剥端口
  ok   带 userinfo 的 authority 也被正确剥掉
  ok   特殊 scheme 的双斜杠路径不被误伤：http://a//b 的 pathname 仍是 //b
  ok   没有 //authority 的串不被误伤：foo:/bar 的 hostname 仍为空
  ok   data: 之类不被误伤
  ok   垫片幂等：再装一次结果不变
--- B. 规范内核（Node 原生 URL）+ 垫片：必须零副作用 ---
  ok   特性探测短路：根本没替换 hostname getter
  ok   规范内核行为不受影响
  ok   规范内核 protocolOf 本来就正确
  --- URL authority 差分测试：通过 15 / 失败 0
```

已接入交付门禁。三套测试现在一起跑：`check.mjs`（120）+ `settle-test.mjs`（6）+ `url-authority-test.mjs`（15）。

## 28. 1.3.9 装完后怎么确认修好了

文件预览应当直接显示内容。日志里 `recs` 的读数应当从

```json
"proto":"UNDEFINED","st":"none","u":"dsh-resource:||78"
```

变成

```json
"proto":"file","st":"live","u":"dsh-resource:|file|72"
```

`st` 变 `live` 就是彻底通了。顺带，这个内核偏差还会影响 `sidebar-right` 里
`pathOf()` 的标签类型匹配，以及 `plan` / `changes-review` 等其它 `dsh-resource://` 协议，
所以这一版应当会一并改善。
