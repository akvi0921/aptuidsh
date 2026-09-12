# 官方 Web UI 报错：Iterator is not defined

## 现象

环境装好、后端在 3081 正常监听，但点「官方 Web UI」后整页报错：

```
HARNESS
Failed to load plugins
failed to import loader entry 9dd0f9bc (@deepseek-ai/dsh-client-ui-sidebar-documentpreview):
Iterator is not defined
```

## 根因

`dsh-client-ui-sidebar-documentpreview` 里内嵌了 PDF.js，其中有一句**特性检测**：

```js
"function" != typeof Iterator.prototype.join
  && (Iterator.prototype.join = function (e) { return [...this].join(e) });
```

`Iterator` 是 **Chromium 122（2024-02）才引入的 JS 全局对象**（Iterator Helpers 提案）。
在版本较旧的系统 WebView 上它是 `undefined`，于是 `Iterator.prototype` 直接抛
`ReferenceError: Iterator is not defined`，导致该插件模块导入失败 →
dsh 的客户端模块加载器把它报成「Failed to load plugins」。

全 dsh 客户端包里**只有这一处**用到 `Iterator`（已逐包扫描确认）。

## 修复：注入兼容性垫片

在页面**任何脚本执行之前**注入一段 `Iterator` polyfill：

- 用 `WebViewClient.shouldInterceptRequest` 只拦主框架、路径为 `/` 或 `/index.html` 的根文档；
- 用 `CookieManager.getCookie()` 取 WebView 自己的 Cookie 重新拉取该文档；
- 把垫片插到 `<head>` 之后（`WebPolyfill.inject`），返回改写后的 `WebResourceResponse`；
- 其它请求一律 `return null`，交回 WebView 正常加载；任何异常都退化为「不拦截」，
  保证最坏情况只是没有垫片，而不是打不开页面。

垫片不只是「让 `Iterator.prototype` 存在」，而是给出语义基本完整的 Iterator Helpers
（惰性求值）：`from / concat / map / filter / take / drop / flatMap / toArray /
forEach / reduce / some / every / find / join` + `Symbol.iterator`。

## 验证

**1. 语义验证**（node 里模拟旧 WebView：先 `delete globalThis.Iterator`）

```
注入前 typeof Iterator = undefined
注入前访问 Iterator.prototype -> ReferenceError: Iterator is not defined   ← 精确复现原报错
注入后 typeof Iterator = function
PDF.js 特性检测执行成功, typeof Iterator.prototype.join = function
map/filter/toArray: [ 6, 4 ]
take/drop: [ 2, 3 ]
flatMap: [ 1, 2, 3 ]
concat: [ 1, 2, 3 ]
reduce/some/every/find/join: 6 true true 2 a-b
```

**2. 注入点验证**（对真实 dsh 页面实测）

```
垫片位置                = 41
模块加载器引导脚本位置  = 163
文档内所有 <script 位置 = [41, 119, 3869, 3966, 26425, 26877]

✅ 垫片紧随 <head> 之后      : True
✅ 垫片早于模块加载器引导    : True
✅ 垫片早于所有其它 script   : True
```

**3. 现场取证**：`onPageFinished` 里会向页面查询 `typeof Iterator` 并把结果写进运行日志，
出问题时控制台能直接看到垫片是否生效，同时记录 `WebView UA`（内核版本）。

## 备注

原生 Compose 界面（继承自 dsh-aui）是主界面，不受此问题影响；
官方 Web UI 是兜底入口。若后续把系统 WebView 升级到 Chromium 122+，
垫片会自动识别并跳过（`if (g.Iterator && g.Iterator.prototype) return;`），无副作用。
