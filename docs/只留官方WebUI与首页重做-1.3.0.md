# APTUIDSH 1.3.0 · 只留官方 Web UI + 首页重做 + 设置页上下布局

> 版本：1.3.0（versionCode 18）　日期：2026-09-24
> 源码从 **22,886 行降到 4,389 行**（-81%）

---

## 一、设置页：左右布局 → 上下布局（注入 CSS 覆盖层）

### 1.1 根因（这次不是猜的，是把它自己的 CSS 挖出来了）

官方设置弹窗的样式在 `dsh-client-ui-settings-general` 的内联 CSS 里，关键两条：

```css
/* 弹窗本体：横向 flex —— 左导航 | 右内容 */
.VOzbGW_panel { width:800px; max-width:calc(100vw - 48px); display:flex; border-radius:32px; overflow:hidden }
/* 左侧导航：固定 188px 的竖列 */
.VOzbGW_nav   { display:flex; flex-direction:column; flex:none; width:188px; padding:22px 12px 0; gap:18px }
/* 每条设置行：左右两端对齐 */
.Pt1bsG_row   { display:flex; justify-content:space-between; align-items:center; gap:24px; padding:16px 0 }
```

`width:800px` 在手机上被 `max-width:calc(100vw - 48px)` 压到约 345px，而左边导航**固定吃掉 188px**，
内容列只剩一百多像素；设置行又是 `space-between`，控件靠右占位后左列被压到几十像素。
中文的 `min-content` 就是**一个汉字**，于是标题与说明被逐字换行竖排 —— 正是实测看到的「变形」。

> 结论：**不是 WebView 缺特性**。布局用到的 `flex`/`grid`/`:has()`/`@container`/`@media (width<=N)`
> 在 Chrome 114 上全都支持；dsh 0.1.5 与 0.1.7 的 CSS 新特性集合也几乎一致。
> 纯粹是「桌面双栏弹窗被塞进手机宽度」。

### 1.2 覆盖层

`WebPolyfill.kt` 新增 `CSS` 常量，随垫片一起注入 `<head>`：

- 弹窗面板 → `flex-direction: column`（导航在上、内容在下）
- 左侧 188px 竖列导航 → 顶部横向一排（`flex-direction: row`，自身横向滚动）
- 窄屏（≤560px）下设置行 → 标题/说明在上、控件在下

### 1.3 为什么必须 `!important`

dsh 的插件样式是**运行时** `document.head.appendChild(<style>)` 注入的，永远排在我们注入的
`<style>` **之后**；两边同为单类选择器时后者胜出。所以覆盖层里所有布局属性都带 `!important`
——这不是偷懒，是这条链路上唯一稳的做法（门禁里有一条断言专门钉住这点）。

### 1.4 ⚠ 与 dsh 版本的耦合（升级必查）

选择器用的是 dsh 的 CSS Module 类名前缀（`[class*="VOzbGW_"]`、`[class*="Pt1bsG_row"]`），
用 `class*=` 前缀匹配可以抗哈希后缀变化，但**前缀本身会随 dsh 重建该 CSS 文件而变**。
因此：

- **升级内置 dsh 后必须重新核对这几个前缀**（方法见下）；
- 新增 `LAYOUT_CHECK` 自检脚本：设置弹窗一出现就检查 `flex-direction` 是否真的变成
  `column`，把 `applied` / `stale` 写到 `window.__aptuidshSettingsLayout`；
- 门禁 `tools/polyfill-test/run.sh` 的 **D 组**钉住覆盖层的存在与完整性。

**重新核对前缀的方法**（dsh 升级后跑一次）：

```bash
# 在解压好的 rootfs 里找设置弹窗的 CSS（关键特征：固定 188px 宽的 nav + 800px 的 panel）
grep -rho 'width:188px[^}]*' <rootfs>/usr/local/lib/node_modules/@deepseek-ai/dsh-client-ui-settings-general/lib/client.js
grep -rho 'width:800px[^}]*' <rootfs>/usr/local/lib/node_modules/@deepseek-ai/dsh-client-ui-settings-general/lib/client.js
# 顺带核对设置行的 flex 左右布局
grep -rho '\.[A-Za-z0-9]*_row{[^}]*justify-content:space-between[^}]*}' <rootfs>/usr/local/lib/node_modules/@deepseek-ai/dsh-client-ui-settings-general/lib/client.js
```

---

## 二、移除自研原生 UI：前端只留官方 Web UI

### 2.1 删掉了什么（约 18,500 行）

| 类别 | 文件 | 行数 |
|---|---|---|
| 对话主界面 | `ChatActivity.kt` | 2,818 |
| 协议层（包 `net/`） | DshClient / EventStream / ApiCompat / BalanceClient / MuxClient / DshGateway / WaterfallRegistry / ModelCatalogSignals | 4,268 |
| 原生 UI 组件（包 `ui/` 除 EnvPanel+theme） | ConversationView / Sidebar / Composer / ComposerArea / MaterialDialog / WorkspacePickerDialog / BackendParser / SessionLog / SettingsDialog / MainContent / MessageModels / RequestCards / HomeScreen(旧) / HomeViewModel / InterjectFloatingBall / ConsumptionStatsDialog / TodoPanel / ApiKeyDialog / SessionGroups / StreamLineBatcher | 9,250 |
| 文件层 | FileBrowserActivity / FileViewerActivity / PlayerActivity / 包 `file/` | 1,864 |
| 壳层零碎 | AppBanner / AppRuntime / InstallReceiver | 128 |
| 测试台 | `tools/jvmtest/`（它测的就是被删掉的 ApiCompat/MuxClient） | — |
| 资源 | `drawable/ic_tool_*.png`(26) / `ic_side_menu.xml` / `drawable-nodpi/*`(23) | — |

### 2.2 保留了什么

```
app/src/main/java/com/aptuidsh/kui/
├── MainActivity.kt          首屏 · 环境控制台（launcher）
├── WebUiActivity.kt         官方 Web UI（内嵌 WebView）—— 现在唯一的前端
├── EnvConsoleActivity.kt    环境控制台（日志 / 自检 / 重装）
├── WebPolyfill.kt           WebView 兼容垫片 + 窄屏布局覆盖层
├── AptuidshApp.kt           Application（崩溃自留地 / DNS 同步 / 自动引导）
├── env/                     proroot+rootfs 生命周期：ProrootEnv / RootfsInstaller /
│                            DshBackend / DshService / DshAuth / EnvLog
└── ui/                      EnvPanel.kt（控制台界面） + theme/Theme.kt
```

**这是有意的分工**：原生侧只负责「把内置 Linux 环境管起来」，其余全部交给官方 Web UI
——官方界面永远与后端版本严格同步，不必再维护一份会漂移的协议适配层。

### 2.3 连带变化

- **权限**：移除 `REQUEST_INSTALL_PACKAGES`（原本只服务于已删的「APK 直装」）。
  `MANAGE_EXTERNAL_STORAGE` 保留 —— guest 把 `/storage/emulated/0` 绑定为 `/sdcard`，
  工作区默认落在 `/sdcard/APTUIDSH`；未授权时优雅回退到 `/root/workspace`。
- **Manifest**：只留 MainActivity / WebUiActivity / EnvConsoleActivity / DshService，
  去掉文件伺服 Provider 与 InstallReceiver。
- **`AptuidshApp`** 不再向协议层注入版本号（协议层已不存在），删掉只服务于它的 `readDshVersion()`。

---

## 三、首页（环境控制台）重做

原首屏是「客户端式」的：连接状态条 + 品牌卡 + `host.describe` 后端信息卡 + 横幅图（点进对话页），
四五个按钮挤在一行。现在按**四块自上而下**重排：

| 块 | 内容 | 设计要点 |
|---|---|---|
| ① 状态卡 | 状态点 + 一句人话（+ 安装时一条进度条） | 扫一眼就知道**能不能用**；不再重复显示「已连接」这类次要信息 |
| ② 信息卡 | 后端地址 / 端口状态 / 环境占用 / 内置 dsh（含「已装 x → 内置 y」）/ 后端 PID | label-value 两列，只留排障真正会看的字段 |
| ③ 操作卡 | **一个主按钮占满整行**（安装并启动 / 启动后端 / 更新环境，三态互斥）+ 下一排两个等宽次按钮（停止 / 重启） | 主次分明；任何时候只有一个「该按的」 |
| ④ 入口区 | **整宽主色大按钮「打开官方 Web UI」**（高 52dp）+ 描边「环境控制台」 | Web UI 才是主入口，给最大最显眼的位置；后端没起来时按钮禁用并说明原因 |

其它改动：删掉「后端信息卡」（它依赖已被移除的 `host.describe` 合成）、删掉横幅图、
品牌信息压缩成一行标题（不再占一整张卡）；所有分区卡统一圆角 14dp + 一致的标题与分隔线样式。

---

## 四、验收

| 层次 | 方法 | 结果 |
|---|---|---|
| 编译 | `gradle :app:assembleDebug` | 通过 |
| 垫片门禁 | `bash tools/polyfill-test/run.sh` | **99 / 0**（新增 D 组 14 条钉住布局覆盖层） |
| 体积 | APK 内不再有原生 UI 的 dex/资源 | 见交付说明 |
| 源码 | 行数 | 22,886 → **4,389** |

---

## 五、已知取舍

1. **设置页覆盖层依赖 dsh 的 CSS 类名前缀**，升级 dsh 后需按 §1.4 重新核对；
   失配时不会报错，只是恢复成左右布局（自检会写 `stale`）。
2. **行内设置只在 ≤560px 生效**（手机必然是），宽屏下保持 dsh 原本的左右行布局。
3. 原生侧不再有任何 dsh 协议能力 —— 这是刻意的：**不再维护会漂移的协议适配层**。
   由此协议层门禁 `tools/jvmtest/` 一并移除。
