# 更新日志

本文件记录**面向使用者**的版本变化；每个版本的详细工程记录在 `docs/` 下。

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

- 自研原生前端全部移除，只保留官方 Web UI（1.3.0 起）；源码 22,886 行 → 约 4,600 行。
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
| 1.3.9 | 修 `URL` authority（文件打不开的真根因） | `docs/WebView兼容-两个根因与取证-1.3.4至1.4.0.md` |
| 1.3.7 | 修 boot 竞态 | 同上 |
| 1.3.0 ~ 1.3.6 | 只留官方 Web UI、首页与环境控制台重做、设置页窄屏布局 | `docs/只留官方WebUI与首页重做-1.3.0.md` |
| 1.2.x 及更早 | 升级 dsh 0.1.7-rc.1、镜像打包与安装校验 | `docs/升级dsh-0.1.7-rc.1与WebView垫片二轮.md`、`docs/真机故障-解压失败根因.md` |
