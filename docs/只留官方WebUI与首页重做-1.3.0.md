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

---

## 六、追加修复（1.3.1）：环境控制台重做 —— 日志被压没了

### 6.1 现象与根因

用户截图：整页被「环境事实核查」那一大坨纯文本 + 一排大按钮占满，**日志区在屏幕外看不见**。

根因在 Compose 布局，不在数据：

```kotlin
Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {   // ← 只有 fillMaxWidth
    ...
    Card(modifier = Modifier.fillMaxWidth().weight(1f)) { ...日志... }
}
```

外层 `Column` 的高度是 **wrap-content（不受限）**，而 Compose 里权重子项只有在高度受约束时
才拿得到剩余空间 —— 于是日志卡实际拿到 **0 高度**，直接被压没。
页面又没有整体滚动，超出的部分就被屏幕裁掉（截图里「开源许可」卡片被切在底部正是这个原因）。

### 6.2 重构：让日志成为主角

新布局（`ui/EnvPanel.kt`，自上而下）：

```
┌ 顶栏：‹返回 · 环境控制台 ················· ● 状态词
├ 概览（紧凑 3 行）：端口 | 内置 dsh ；后端地址
├ 操作条（小按钮 · 横向可滑）：启动 停止 重启 │ 运行自检 重装环境 卸载环境
├ 数据条（小按钮 · 横向可滑）：复制日志 复制崩溃报告 导出完整报告
├ 提示行（仅在有内容时出现，可 ✕ 关掉）：toast
├ 日志头：日志 · 共 N 行 ······ [只看 dsh] [跳到最新]
├ 日志区 ★ weight(1f) 吃掉剩余全部高度，自身滚动
└ 折叠区（默认收起）：环境事实核查 / 自检结果 / 开源许可与致谢
```

关键改动：

| 问题 | 改法 |
|---|---|
| 日志被压成 0 高度 | 外层改 `fillMaxSize()`，日志区 `weight(1f)` —— **这条是本次的根因修复** |
| 按钮太大、一排占满屏 | 新增 `MiniButton`（高 **34dp**，官方默认 40~48dp）；按钮条 `ButtonBar` 用 `horizontalScroll`，放不下就横滑，**绝不换行挤占纵向空间** |
| 「环境事实核查」一大坨纯文本常驻 | 抽成 `CollapsibleSection`（默认**收起**，点标题展开；展开后 `heightIn(max=190dp)` + 内部滚动） |
| 「开源许可」卡片常驻且被裁 | 同样改折叠区（默认收起，但**标题常驻可见**，licence 署名要求仍满足） |
| 自检结果把日志顶下去 | 改成可关闭的折叠区（点标题即关闭），`maxBodyHeight=130dp` |
| 六行 label-value 信息卡太高 | 概览压成 2 行等宽 KV + 1 行后端地址 |
| 整页当大长条滚 | 只有日志区内部滚动，其余固定 |

### 6.3 验收

- `aapt2 dump badging` → `versionCode 19 / versionName 1.3.1`；
- dex 核对：新控制台的关键串（「环境事实核查（启动器…」「跳到最新」「只看 dsh」「显示全部」
  「复制崩溃报告」「自检结果（点开查看）」）均在包内；
- 布局自证：外层 `fillMaxSize` + 日志区 `weight(1f)`（见 §6.1 的反例）。

---

## 七、追加修复（1.3.2）：设置项菜单消失了 + 横屏要保留左右布局

### 7.1 根因一：`[class*=...]` 子串匹配把菜单项裁掉了

上一版覆盖层用的是子串匹配：

```css
[class*="VOzbGW_nav"] { flex-direction: row !important; width: 100% !important; /* … */ }
```

`class*=` 是**子串**匹配，所以这条同时命中了 `VOzbGW_navTitle`、`VOzbGW_navList`、
`VOzbGW_navCell` —— 于是 `width:100%` 被套到了标题上：

```
[VOzbGW_navTitle  width:100%, flex:none] [VOzbGW_navList …]   ← 总宽 200%
   ↑ 标题独占整行，后面的四个菜单项被挤出行外，再被 nav 的 overflow:hidden 裁掉
```

表现就是用户看到的：**只剩「设置」两个字，通用设置 / 模型 / 内置插件 / Agent 预设 四个菜单项全不见了**。

**修法**：改用 `[class~="X"]`（按空白分隔的 **token 精确匹配**）。
`class~="VOzbGW_nav"` 不会命中 `VOzbGW_navTitle`，因为它是一个不同的完整类名。
门禁新增两条：① 覆盖层里**禁止出现 `class*=`**；② `navTitle` 上**不得出现 `width:100%`**；
并加了一条**自证**——统计 `class*= "VOzbGW_nav"` 会误伤几个类名（当前 3 个），
证明这条护栏不是空的。

### 7.2 根因二：覆盖层写成了无条件生效

上一版整段覆盖没有媒体查询，横屏时也把官方布局掰成上下 —— 而横屏视口够宽，左右布局本来更好用。

**修法**：整段包进

```css
@media (orientation: portrait) and (max-width: 600px) { … }
```

- **手机竖屏** → 上下布局（导航在上、内容在下，设置行也上下）；
- **横屏 / 宽屏** → 完整保留官方原本的左右布局，一个字节都不动。

`LAYOUT_CHECK` 自检同步区分：不在竖屏时写 `n/a-landscape`（而非误报 `stale`）。

### 7.3 验收

- `aapt2 dump badging` → `versionCode 20 / versionName 1.3.2`；
- dex 核对：`class~="VOzbGW_panel"` / `class~="VOzbGW_navTitle"` / `orientation: portrait` /
  `n/a-landscape` 均在包内；旧的 `class*="VOzbGW_nav"` 已清除；
- 垫片门禁 `bash tools/polyfill-test/run.sh` → **105 / 0**（D 组新增 token 匹配、竖屏限定、
  navTitle 宽度、自证共 5 条）。

### 7.4 教训（又踩了一次「断言被自己的注释绊倒」）

加护栏时我把反例写进了 CSS 注释（`…不能用 [class*="X"]…`），结果
`!CSS.includes('class*=')` 立刻报 FAIL —— 断言命中的是我自己的说明文字。
**凡是「不许出现 X」的断言，必须先剥掉注释再匹配**（项目经验里早有这条，这是第二次踩）。
现在 D 组所有断言一律基于 `CSS_CODE = CSS.replace(/\/\*[\s\S]*?\*\//g, '')`。

---

## 八、追加修复（1.3.3）：设置内容撑破弹窗、不能内部滚动

### 8.1 现象

导航与内容都正常了，但内容一多就**超出弹窗高度、被裁掉且滚不动**（截图里最后一个
「开发者工具」只露出一半，下面的内容彻底看不到）。

### 8.2 根因（两个叠在一起）

**① flex 子项默认拒绝收缩 —— 这是滚动失效的直接原因。**

官方 CSS 里内容列只有横向约束，没有纵向：

```css
.VOzbGW_content { display:flex; flex-direction:column; flex:1; min-width:0 }   /* 没有 min-height */
.VOzbGW_options { flex:1; min-height:0; overflow-y:auto; padding:0 24px 24px }
```

`min-height` 的初始值是 `auto`，对 flex 子项而言等于「**不小于内容高度**」。
于是 `content` 拒绝收缩到内容高度以下 → 它把弹窗撑破 → 弹窗的 `overflow:hidden` 把溢出部分一裁，
`options` 永远拿不到受限高度，自然也就没有滚动条。

这就是 flex 布局那条著名铁律：**想让 flex 子项内部滚动，必须显式给它（以及它的每一层祖先）`min-height:0`。**

**② 弹窗高度用的是 `100vh`（布局视口），不是可见视口。**

```css
.VOzbGW_panel { height: min(800px, calc(100vh - 2 * max(24px, var(--dsh-frame-top-clearance, 24px)))) }
```

在 WebView 里布局视口与可见视口可能不等，弹窗因此可能比看得见的区域还高。

### 8.3 修法（都在竖屏媒体查询里）

```css
[class~="VOzbGW_panel"] {
  flex-direction: column !important;
  height: min(800px, calc(100vh  - 24px)) !important;   /* 老内核兜底 */
  max-height:           calc(100vh  - 24px) !important;
  height: min(800px, calc(100dvh - 24px)) !important;   /* dvh 跟可见视口，优先 */
  max-height:           calc(100dvh - 24px) !important;
}
[class~="VOzbGW_content"] { min-height: 0 !important; overflow: hidden !important; }
[class~="VOzbGW_options"] { min-height: 0 !important; overflow-y: auto !important;
                            -webkit-overflow-scrolling: touch !important; }
```

- 先写 `vh` 再写 `dvh`：不支持 `dvh` 的内核会把后一条当非法声明丢掉，自动沿用 `vh`；
- `dvh`（Chrome 108+，本机 114 支持）跟的是**可见视口**，正好解决根因②。

### 8.4 验收

- `aapt2 dump badging` → `versionCode 21 / versionName 1.3.3`；
- dex 核对（注意 `grep` 要加 `--`，否则开头的 `-` 会被当成选项）：
  `class~="VOzbGW_content"` / `min-height: 0 !important` / `calc(100dvh - 24px)` /
  `-webkit-overflow-scrolling` / `overflow-y: auto !important` 均在包内；
- 垫片门禁 `bash tools/polyfill-test/run.sh` → **109 / 0**（D 组再增 4 条：
  弹窗限高用 dvh、内容列 `min-height:0`、options 可纵向滚动，以及一条自证
  「内容列规则里确实带上了 min-height」）。
