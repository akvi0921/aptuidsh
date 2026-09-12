# v1.1.4：WebView 向下兼容层 + 日志可见性

## 用户诉求与判断

> "控制台的复制按钮没看到。无法创建会话的问题很可能还是我们的系统的 webview 版本过低，
>  你需要找到一个向下兼容的解决方案……还有，确保 dsh 的启动日志进入环境控制台日志中"

### 1) 复制按钮为什么没出现（我的疏漏）

上一版我用了 `str.replace` 批量插桩，**那一次替换的匹配串没命中，脚本却照常打印了成功日志**，
于是按钮根本没进代码。已用带唯一性校验的方式重新插入，并新增：

- **一键复制全部日志**（整宽按钮，最醒目）
- 复制崩溃报告 / 导出完整报告
- **日志筛选**：`只看 dsh 启动日志` ↔ `显示全部日志`
- **跳到最新** 按钮
- 自动下滚改为**只在用户本来就在底部时**才触发（此前会把正在翻阅历史的用户强行拽回底部，
  这正是"看不到 dsh 启动日志"的直接原因——启动日志被后面的 RPC 明细挤出了可视区）

### 2) WebView 向下兼容层（用户的判断是对的）

实测用户机型系统 WebView 是 **Chrome 114**，而 dsh 官方界面按现代浏览器构建，
用到了一批 116~122 才有的 API。原先只补了 `Iterator`，现扩展为完整兼容层：

| API | 需要的内核版本 |
|---|---|
| `AbortSignal.any` / `timeout` | Chrome 116 |
| `Object.groupBy` / `Map.groupBy` | Chrome 117 |
| `Promise.withResolvers` | Chrome 119 |
| `Symbol.dispose` / `Symbol.asyncDispose` | Chrome 119（`using` 声明的编译产物依赖它们） |
| `Array.fromAsync` | Chrome 121 |
| `Iterator` + Iterator helpers | Chrome 122 |
| `Set` 集合运算（union/intersection/difference/…） | Chrome 122 |

全部按"存在则跳过"补齐，新内核上零副作用。

**验证**（node 里先删掉这些 API 模拟 Chrome 114，再 eval 垫片）：

```
注入前: Iterator=undefined withResolvers=undefined AbortSignal.any=undefined groupBy=undefined
注入后: Iterator=function  withResolvers=function  AbortSignal.any=function  groupBy=function
withResolvers: 42
AbortSignal.any: true
groupBy: {"odd":[1,3],"even":[2,4]}
Set.union: [1,2,3]    Set.intersection: [2]
Array.fromAsync: [2,4,6]
Iterator helpers: [ 2, 4, 6 ]
PDF.js 特性检测通过: true
```

### 3) 关于"不使用系统浏览器内核"

WebView 无法替换：Android 只允许系统 WebView / Chrome 作为渲染内核，
应用内不能自带 Chromium（体积与 Play 政策都不允许）。
**但"自带内核"其实不必要**——缺的都是纯 JS 标准库 API，垫片即可完全覆盖，
比起换内核代价小得多、也更可控。原生 Compose 界面则本来就与 WebView 无关。

## 4) 顺带发现的镜像卫生问题

`dist/rootfs.tar.gz`（21:57 构建）里**带进了调试期的 53 条测试会话与 `.credentials.yaml`**，
用户设备上因此会看到不属于自己的会话。`tools/build-rootfs.sh` 已补：

- 扩充清理项（sessions / storages / attachments / llm-* / .agent-presets / 凭据）；
- **构建后逐项自检**：任一残留或 `root/.dsh` 内能搜到 `sk-` 就直接中止构建。
