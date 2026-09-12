# v1.1.2：原生 UI 崩溃 + 官方 Web UI 垫片失效

## 一、官方 Web UI：垫片为什么没生效

用户日志：

```
WebView UA: Mozilla/5.0 (Linux; Android 12; JAD-AL00 ...) ... Chrome/114.0.5735.196 Mobile Safari/537.36
跳过垫片注入：根文档返回 HTTP 303
页面内 typeof Iterator = "undefined"
```

两件事被一锤定音：

1. **内核是 Chrome 114**，而 `Iterator` 需要 **122+** —— 所以垫片确实是必需的；
2. **垫片根本没注进去**：首次加载的是 `/?token=<launchToken>`，服务端返回 **303** 重定向，
   而 WebView 跟随重定向后的那次请求 **不会再经过 `shouldInterceptRequest`**，
   于是注入逻辑一次都没执行（`typeof Iterator` 仍是 `undefined`）。

### 修复：拦截器自己跟随重定向链

```
第 1 跳: /?token=… → HTTP 303, Location=/, Set-Cookie 1 个   ← 自己捕获并存入 WebView CookieJar
第 2 跳: /          → HTTP 200, 文档 27724 字节
垫片位置 = 41 == 文档首个 <script> 位置                      ← 注入成功
CookieJar: ['dsh-auth-…']
```

- 最多跟 5 跳；每一跳都把 `Set-Cookie` 写进 `CookieManager`（token→Cookie 交换就靠这步）；
- 只对最终 200 的 HTML 注入；
- 任何异常都退化为「不拦截」，保证最坏情况只是没有垫片，而不是打不开页面。

## 二、原生 UI：「添加工作区」崩溃重启

`DshClient` 的 RPC 在 `dsh-rpc` 线程池（后台线程）执行，而回调体里大量是 UI 代码。
其中 **`Toast` 在非主线程调用会直接抛异常**：

```
java.lang.RuntimeException: Can't toast on a thread that has not called Looper.prepare()
```

全项目扫出 **9 处 Toast 位于 RPC 回调内**，每一处都是必崩：

| 文件 | 位置 |
|---|---|
| `ui/WorkspacePickerDialog.kt` | 395 / 403 / 522 / 529（创建/打开工作区） |
| `ChatActivity.kt` | 1645 / 1744 / 1751 / 2326 / 2332 |

### 修复：回调统一在主线程投递

与其逐个改调用点（以后新写的回调还会再犯），直接在 `DshClient` 收敛：
所有 `onResult` / `onError` 经 `deliver()` 投递——已在主线程则直调，否则 `Handler(mainLooper).post`。

**为什么安全**：所有调用方要么是 Compose UI，要么是 `suspendCancellableCoroutine`
（`ChatActivity.suspendRpc` 用 `scope.launch { cont.resume(...) }`，非阻塞等待），
主线程投递不会造成死锁。

顺带修掉 `AppBanner.show` 的 Toast 兜底分支（`ctx` 不是 Activity 时会在调用线程直接 show）。

## 三、环境控制台新增（用户要求）

- **一键复制日志**：把全过程日志整段写入剪贴板
- **复制崩溃报告**：读取 `Android/data/com.aptuidsh.kui/files/crash.txt` 并复制
- **导出报告**：把环境事实 + 自检 + 全过程日志写成 `env-report.txt`
- 日志区**自动滚到底**（此前固定停在最早几行，看起来像「没有输出」）
- 日志标题显示总行数，日志正文顶部显示日志文件绝对路径
