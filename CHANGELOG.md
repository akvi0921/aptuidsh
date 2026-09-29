# 更新日志

本文件记录**面向使用者**的版本变化；每个版本的详细工程记录在 `docs/` 下。

---

## 1.5.0 —— 内置 dsh 升级到 0.2.0-rc.2（2026-09-25）

内容：`@deepseek-ai/dsh 0.2.0-rc.2` + Ubuntu 24.04.5 arm64 + Node.js 22.22.2，
前端仍只有官方 dsh Web UI。

> 关于版本号：npm 上**不存在 2.0/2.x**，`latest` 与 `next` 都指向 `0.2.0-rc.2`；
> 官方口径里的「2.0」即指该版本。

### 变更

- 内置 dsh `0.1.7-rc.1` → **`0.2.0-rc.2`**（541 个包；官方包 288 + 177 个顶层依赖）。
- **客户端方法表 119 → 134：纯新增 15 个、零删除** —— 这一跳不含破坏性变更
  （对比 0.1.5→0.1.7 曾删除 `subagents/list`）。新增集中在三个新命名空间：
  `productAnalytics`（产品分析上报）、`schedule`（定时任务）、`userQuestions`。
- 新增 4 个客户端插件：`dsh-client-product-analytics`、`dsh-client-shortcuts`、
  `dsh-client-ui-shortcuts`、`dsh-client-ui-settings-session-log`（均 `platform: web`）。
- WebView 兼容层**无需改动**：缺口清单与 0.1.7-rc.1 逐条一致（脚本 84→124 个），
  `BOOT_SETTLE` 与 URL authority 两个补丁的挂点均未变化（详见 `WebPolyfill.kt` 顶部的体检记录）。
- 窄屏布局覆盖层依赖的 dsh 类名前缀未失配，**设置页竖屏布局无需返工**。
- 镜像体积：gzip 203,217,210 → **208,732,580** 字节；解压后 794M → 805M，文件数 32,700 → 33,059。

### 你需要知道的新能力

- **`productAnalytics`** —— 本版新增的数据上报能力（`productAnalytics/enabled|report|watchPolicy`）。
  本项目**未改动其默认行为**；是否启用由 dsh 自身决定，介意的话请在 Web UI 设置里确认。
- **`schedule`** —— 新增的定时任务能力，随 dsh 一起运行，APP 侧未做特殊处理。

### 工程

- 新增 **`tools/upgrade-diff/`**：把「升级会踩到的东西」（方法表 / 设置页 CSS 前缀 /
  boot 内核标记 / `protocolOf` 原文 / 各包版本 / 原生包）抽成快照并机械 diff，
  下次升级可直接复用。它会自动识别 **local（平铺）与 global（嵌套）** 两种安装布局 ——
  指错了会得到「119 个方法全被删除」这类假警报。
- `tools/build-rootfs.sh` 的跨平台二进制清理改为**白名单式**（只保留 `linux-arm64`）：
  旧写法逐个列举要删的平台，于是 0.2.0 新增的 `node-pty/prebuilds/linux-x64/` 漏了过去。
- 第三方许可文档补充了 0.2.0 新增的三个原生依赖（`sherpa-onnx`、`node-addon-system`、
  `node-addon-require-builtin`）。

---

## 1.4.0 —— 第一个开源正式版（2026-09-25）

内容：`@deepseek-ai/dsh 0.1.7-rc.1` + Ubuntu 24.04.5 arm64 + Node.js 22.22.2，
前端**只有官方 dsh Web UI**，原生侧只负责装/启/停环境。

### 修复

- **工作区文件打不开（预览显示「文件资源服务不可用」）** — 根因是低版本 WebView
  **不解析非特殊 scheme 的 authority**：`new URL('dsh-resource://file/…')` 的 `hostname`
  为空、`pathname` 多出 `//file`，于是 dsh 的资源模型认不出这个地址属于 `file` provider。
  垫片里修正了 `URL.prototype` 的 `hostname` / `pathname`（先特性探测，现代内核零副作用）。
  同一个偏差还会影响按地址模式匹配标签类型，以及 `plan`、`changes-review` 等其它
  `dsh-resource://` 协议，因此一并改善。
- **整页 `Failed to load plugins`** — 根因是 dsh 的 web boot 竞态：内核只等「模块加载完」
  就一次性、无重试地要求所有插件 active，而 `remote.*` 命名空间要等 22 次串行挂载才出现。
  现在让 `loader.await()` 等到插件名单收敛（有界、可退让，实测正常收敛 58~231 ms）。
- **设置页在手机上竖排挤成一条** — 窄屏覆盖层把官方设置弹窗由「左导航 + 右内容」
  改为「导航在上、内容在下」（横屏仍保留官方左右布局）。
- **`tools/build-apk.sh` 在构建失败时仍报「构建完成」** — 现在显式检查 Gradle 退出码。

### 变更

- 自研原生前端全部移除，只保留官方 Web UI（1.3.0 起）：原有前端相关代码（22,886 行）整体删除，
  现全应用（含 `WebPolyfill.kt` 兼容层）Java/Kotlin 共 **5,203 行**。
- 内置 dsh 升级到 **0.1.7-rc.1**（npm `next` 标签），补齐 Chrome 116~147 的 API 缺口。
- 首页重做为四块式（状态 / 信息 / 操作 / 入口），环境控制台重做布局（日志可滚动、按钮不再挤压）。

### 工程

- 交付前门禁 `bash tools/polyfill-test/run.sh`：**141 项断言**（117 + 9 + 15），
  全部从 `WebPolyfill.kt` 抽真实源码执行，含与 Node 原生实现的差分对拍。
- 新增两个**先复现再修复**的专项测试：`settle-test.mjs`（真实时钟验证 boot 补丁的有界性）、
  `url-authority-test.mjs`（先把 `URL` 换成复刻旧内核缺口的假体，避免「Node 本来就对」的假绿）。
- 本文档与 `LICENSE`（MIT）、`third_party/OPEN-SOURCE.md` 首次齐备。

---

## 1.3.x 及更早

1.3.x 是开源首发前的开发期，逐版记录见 `docs/`：

| 版本 | 主题 | 文档 |
|---|---|---|
| 1.5.0 | 内置 dsh 升级到 0.2.0-rc.2 | 本节 |
| 1.3.9 | 修 `URL` authority（文件打不开的真根因） | `docs/WebView兼容-两个根因与取证-1.3.4至1.4.0.md` |
| 1.3.7 | 修 boot 竞态 | 同上 |
| 1.3.0 ~ 1.3.6 | 只留官方 Web UI、首页与环境控制台重做、设置页窄屏布局 | `docs/只留官方WebUI与首页重做-1.3.0.md` |
| 1.2.x 及更早 | 升级 dsh 0.1.7-rc.1、镜像打包与安装校验 | `docs/升级dsh-0.1.7-rc.1与WebView垫片二轮.md`、`docs/真机故障-解压失败根因.md` |
