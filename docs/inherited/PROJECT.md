# APTUIDSH 安卓客户端 · 项目开发经验书

> 项目：aptuidsh（应用名 APTUIDSH，包 `com.aptuidsh.kui`）
> 版本：1.11.1（versionCode 70）· 20 个源文件（Kotlin 15 + Java 5，约 4500 行 Kotlin）· 20 PNG + 3 XML 资源
> 定位：DeepSeek Harness（dsh，运行于 Termux 本机 `http://127.0.0.1:3081`）的**原生安卓前端 UI 客户端**
> 当前阶段：完整 UI 布局与交互，后端接口未接入（占位数据驱动，协议层已移植就绪）

---

## 1. 项目背景

dsh（DeepSeek Harness）运行在 Termux 中，通过 HTTP JSON-RPC + 双 WebSocket 事件通道暴露给前端。
参考项目 dsh-android（纯 Java + 代码 UI）已实现全功能移植。本项目的目标是**用更现代的 Kotlin + Jetpack Compose 技术栈**，
从零构建一个新的前端客户端，先完成全部 UI/交互，后续接入真实后端数据流。

### 技术栈

| 项 | 值 |
|---|---|
| 语言 | Kotlin（协议层 Java） |
| UI | Jetpack Compose (Material3, BOM 2024.06.00 / Compose 1.6.8) |
| 协议层 | 自 dsh-android 移植（Java：DshClient / EventStream / DshGateway / AppRuntime / AppLog） |
| 构建 | Termux：OpenJDK 17 + Gradle 8.13 + AGP 8.7.3 + Kotlin 2.0.21 + SDK 34 + aapt2 override |
| 兼容 | minSdk 24 / targetSdk 34 / arm64-v8a |
| 依赖 | 仅 androidx + Compose；零网络/图片库（HTTP 自实现，素材内置） |

### 页面结构

```
MainActivity（首屏:连接状态 + 后端信息 host.describe）
   └─ 横幅点击进入
ChatActivity（对话主界面）
   ├─ 多会话窗口（每会话独立状态）
   ├─ 侧边栏（收纳/展开、会话列表、新建会话、设置/工作区入口）
   ├─ 内容区（引导层悬浮层 / 会话层消息流）
   ├─ 统一输入卡片（编辑器+工具区、收缩悬浮球、模型/权限菜单）
   └─ 弹窗（设置、选择工作区目录、后台任务、命令/权限/上下文等）
```

---

## 2. 版本演进史（关键节点）

| 版本 | 内容 | 备注 |
|---|---|---|
| v1.1.0 | 对话主界面骨架：侧边栏收纳/展开、SVG 图标转 VectorDrawable | 图标转换方案 |
| v1.1.1 | 4-9 图标 **SVG 转 VectorDrawable 不可用 → 换 PNG**；收纳/展开布局细化 | 首个大坑 |
| v1.1.2 | 修复侧边栏底部内容不可见（**constraints 高度识别错误**） | 布局锚定方案 |
| v1.1.3-1.1.6 | 图标放大/行高统一/搜索框替换（TextField → **BasicTextField 下划线框**） | 组件替换 |
| v1.2.0 | 内容区 UI（顶部信息栏：模式/Session log/后台任务/对话·轨迹选项卡） | MainContent |
| v1.2.1-1.2.3 | 内容区细节、竖屏展开遮罩、卡片高度机制 | zIndex 层级体系 |
| v1.3.0 | 引导层悬浮层（WelcomeLayer：标题+工作区/预设+compact 输入卡） | 内容双模式 |
| v1.3.1-1.3.4 | **统一输入卡片**（引导层/会话层共用）+ 切换动画（分离容器、graphicsLayer） | 架构重构 |
| v1.3.5-1.3.6 | 添加工作区目录弹窗（文件浏览器） | WorkspacePickerDialog |
| v1.3.7 | 会话层消息渲染（块模型：注入/推理/正文/工具卡、markdown-lite） | ConversationView |
| v1.3.8-1.3.11 | 底部锚点与悬浮遮挡（预留→透空隙→**对齐侧边栏设置行锚点**） | 滚动锚点 |
| v1.3.12 | 输入卡片右滑**收缩为圆形悬浮球**手势 | 手势 |
| v1.3.13 | **多会话窗口状态隔离**（SessionState 统一数据类）+ 会话列表 | 状态架构 |
| v1.3.14-1.3.19 | 会话列表交互与**长按拖拽排序反复踩坑**（自研手势 3 版均异常） | 拖拽难题 |
| v1.3.20-1.3.21 | 引入开源库 **Reorderable**；反编译发现误用 `draggableHandle`（非长按）→ 改 `longPressDraggableHandle` | 引入成熟方案 |
| v1.3.22 | 用户决定**彻底移除拖拽排序**（依赖+动画+代码全清理） | 做减法 |
| v1.3.23-1.3.27 | **设置弹窗**（左菜单+右内容、Agent 预设卡片、外观切换真实生效 via LocalDarkTheme）、工作区图标弹窗 | 设置体系 |
| v1.4.0 | **后端接入**：sidebar 真实 session.list；点击会话 → session.history 渲染真实消息流；顶行真实预设名。**dsh-android 废弃，改研官方 deepseek-harness 协议** | 协议落地 |
| v1.5.0 | **会话列表分组**（移植官方 workspace 树）：按工作区/单列表两档；组头折叠展开；未分组桶；子代理不占行（父行徽标）；blank 仅当前显示 | 官方移植 |
| v1.6.0 | **侧边栏覆盖模式**（不再挤压内容区）+ **实时流式输出**（移植官方 mux 会话事件流：chunk 打字机/assistant message 落定/tool result 回填；seq 防重放+掉帧重拉） | 实时事件移植 |
| v1.7.0 | **输入卡片真实化**（官方 InputBar 语义）：真实发送 session.prompt；session.models 目录 + selectModel；permissions/contextPressure/sessionStats 投影实时统计；停止按钮 session.cancel | 官方输入条移植 |
| v1.7.1 | **输入卡片高度自适应修复**：卡片高度改为 wrapContent + heightIn(min=140dp, max=屏半)；Composer 从 fillMaxSize 改为 wrapContentHeight；编辑器 max 从屏1/6提升到屏1/3 | 编辑器扩展修复 |
| v1.8.1 | **引导层真实后端接入**：工作区选择（connectWorkspace 语义：复用空白会话/session.create 带 workspaceId）；Agent 预设选择（Staging 语义：暂存+立即应用/创建时传入）；移除所有占位数据 | 引导层真实化 |
| v1.8.2 | **Bug 修复**：工作区弹窗路径错误修复（空路径用无参 API）；引导层模型列表初始化加载；发送按钮运行态即时设置（发送后立即变停止） | Bug 修复 |
| v1.8.3 | **Bug 修复**：引导层发送失败（本地占位会话 `blank` 默认为 `false` 导致 `backendLike` 误判为 `true`，需改为 `true`）；内容区滚动定位错误（添加底部锚点 item + delay 等待布局稳定 + animateScrollToItem）；模型列表加载改为官方 `llm.models` 方案（无需 sessionId） | 会话创建+滚动+模型修复 |
| v1.8.4 | **会话层流式输出对齐官方**：滚动机制（atBottom 追踪 + 自动跟随 + 用户中断 + 回到底部按钮）；TurnStatus 流式状态指示器（Deep diving… + 计时器）；消息渲染优化 | 官方流式对齐 |
| v1.8.5 | **侧边栏搜索会话**：对齐官方 deriveSearchResults 客户端过滤（会话标题/预设名/工作区名）；搜索框展开/清除；匹配文字高亮；搜索结果列表 | 搜索功能 |
| v1.8.6 | **设置项真实应用**：Agent 预设和权限设置对齐官方（settings.describe 加载 + settings.update 持久化）；新会话自动使用设置的默认值；设置弹窗使用后端真实预设列表 | 设置真实化 |
| v1.8.7 | **Bug 修复**：新会话模型列表未加载（newSession 中异步加载 llm.models）；流式输出内容跳到顶部（isFollowing 标志 + 底部检测逻辑改进） | Bug 修复 |
| v1.8.8 | **自动化程序方案重构**：移除已有设置传递逻辑（newSession/sendPromptToSession/pickWorkspace）；实现 applySettingsToNewSession 自动化程序（从后端拉取最新配置 + agentPreset.select 应用预设 + 权限更新）；设置修改后重新读取后端配置确认保存成功 | 设置重构 |
| v1.8.9 | **Bug 修复**：自动化程序未正确应用 Agent 预设（本地占位会话 vs 真实后端会话区分处理 + 延迟 500ms 等待初始化）；权限设置 UI 渲染错误（显示名 vs 后端值映射）；sendPromptToSession 保存并应用预设 | Bug 修复 |
| v1.9.0 | **移除本地会话设计**：移除 __boot 本地占位会话；newSession 改为真实创建后端会话（session.create）；sendPromptToSession 简化（移除本地会话检测）；applySettingsToNewSession 简化（所有会话都是真实后端会话）；启动时无会话自动创建 | 架构重构 |
| v1.9.1 | **Bug 修复**：引导层预设显示不同步（selectedPresetId 与 settingsAgentPreset 未同步）；在 applySettingsToNewSession、SettingsDialog 回调、启动加载设置三处同步更新 selectedPresetId | Bug 修复 |
| v1.10.0 | **流式输出结束判断+底部操作栏**：ChatMessage 新增 timing 字段（turnStartTime/firstTokenTime/completedTime）；LiveFold 记录 turn/start、首 token、落定时间；TurnTail 组件（时间+运行时长+TTFT+复制+分支）；session.fork 创建分支会话 | 新功能 |
| v1.10.1 | **Bug 修复**：分支按钮没反应（条件改为 blocks.isNotEmpty）；工具调用时误判为输出结束（新增 turnCompleted 字段，只在 turn/end 时标记完成）；lastSeq 记录用于 session.fork | Bug 修复 |
| v1.10.2 | **Bug 修复**：底部操作栏闪现后消失（applyAssistantMessage 保留 turnCompleted 状态 + sealStreaming 查找最后一条 assistant 消息标记） | Bug 修复 |
| v1.10.3 | **底部操作栏重构**：turnCompleted 从消息字段改为 SessionState 状态（对齐 running 字段）；turn/start → turnCompleted=false，turn/end → turnCompleted=true | 重构 |
| v1.10.4 | **Bug 修复**：历史消息底部操作栏不显示（loadSessionHistory 完成后设置 turnCompleted=true）；所有助手消息都显示底部操作栏（移除 isLastAssistant 限制） | Bug 修复 |
| v1.10.5 | **Bug 修复**：分支按钮不正确（历史消息 lastSeq 未记录，导致 atSeq=0）；BackendParser 解析历史消息时记录 seq 到 lastSeq | Bug 修复 |
| v1.10.6 | **Bug 修复**：分支后原会话消息未移除（fork 成功后重新加载原会话历史消息） | Bug 修复 |
| v1.10.7 | **Fork 对齐官方**：fork 后直接切换到子会话（不重新加载原会话历史），对齐官方 `sessions.open(childId)` 实现 | 对齐官方 |
| v1.10.8 | **Bug 修复**：fork 后子会话显示孤立用户输入（parseSessionHistory 末尾过滤无助手回复的尾部用户消息，对齐官方只渲染完整 turn 的行为） | Bug 修复 |
| v1.11.0 | **模型配置弹窗**：侧边栏搜索图标下方新增素材图标，点击弹出模型配置弹窗；使用 `llm.providers` RPC 获取提供方列表（对齐官方），`credentials.describe` 获取凭据状态；编辑提供方通过 `settings.mutate` + `credentials.set` 保存到后端；删除提供方通过 `settings.mutate` + `credentials.unset`；新增 MaterialDialog.kt + ic_side_material.png | 新功能 |
| v1.11.1 | **模型配置弹窗优化**：首页只显示已配置的提供方；点击"添加提供方"进入添加页，显示所有未配置的提供方，每个右侧可点击编辑配置key；两层结构（已配置列表 / 添加页） | UI 优化 |
| v1.11.9 | **模型配置弹窗完善**：API 地址占位（从 settings.describe 提取 baseURL）；已配置模型列表显示（从 settings.describe 提取 models）；占位逻辑修复（无 baseURL 时显示"提供方默认"） | 功能完善 |
| v1.11.11 | **插话（interject/steer）消息渲染位置修复**：用户气泡渲染数据源从 `agent/inbox/spliced`（入队时刻）改为 `user/message`（真实注入上下文位置），对齐官方 surface 层语义；修正历史快照与实时流两处不一致 | 协议对齐 |
| v1.11.12 | **Bug 修复**：排队插话三个按钮（编辑/删除/立即插入）点击无反应——`session.updateQueue` 请求格式与官方 API 不匹配（用了嵌套 `operations` 数组，应为顶层 `itemId`+`action`）；编辑/立即插入改用后端原生 action，移除前端「先删再发」逻辑 | Bug 修复 |
| v1.11.13 | **插话自动化程序**：设置新增「输出流终止时清空队列」开关；用户点停止后自动检查并清空排队插话（autoClear=true）或保持悬浮球常态化显示（autoClear=false）；悬浮球显示条件扩展为「设置开启 + (助手输出中 或 排队有消息)」；UI 优化：排队列表支持内部滚动、方形无圆角开关（关灰/开红）、✓统一红色 | 新功能 |

---

## 3. 架构设计（最终）

### 3.1 数据层：多会话窗口状态隔离

```
ChatScreen
 ├─ sessions: Map<会话id, SessionState>   ← 浏览器标签页语义
 ├─ currentId
 └─ SessionState（每会话独立）:
     id/title/messages/showWelcome(引导层标志)/composerText(未发送文本)
     /collapsed(卡片收纳)/model/reasoning/permission/updatedAt
     /agentPresetId/agentPresetName(后端预设 id→name)/loading(历史拉取中)
```

核心原则：**切换会话 = 保存当前 + 加载目标**；会话间的消息、引导层/会话层形态、
编辑器未发送文本、模型/推理/权限、卡片收纳状态**互不污染**。

### 3.2 UI 层：zIndex 层级体系

```
内容层 MainContent (z1) < 输入卡片/悬浮层 ComposerArea (z2)
  < 竖屏展开遮罩 (z2.5) < 侧边栏 (z3) < 弹窗/菜单（独立 Window）
```

### 3.3 统一输入卡片（ComposerArea）

- **引导层/会话层共用同一 Composer**（文本/模型/权限等状态受控、由 SessionState 提供）
- 位置动画：引导层「前两行+卡片」视觉相连居中 ↔ 会话层卡片贴底
- 引导层附加内容（标题/工作区/预设）与卡片为**两个独立容器**（各自 graphicsLayer 位移动画，互不牵连布局）
- 右划收缩成圆形悬浮球（引导层禁手势；新开会话自动展开）

### 3.4 消息渲染（ConversationView）

- 块模型对齐 dsh 协议 `content[]`：context-injection / reasoning / text / tool-call
- 用户消息：直角气泡 + 时间戳 + ⧉ 复制
- 助手消息：模型徽标 + 块列表（注入/推理折叠、markdown-lite 正文、工具卡）
- 时间行仅在**流式结束后**打印；列表底部锚点 = 与侧边栏设置行对齐

### 3.5 设置体系

- 设置弹窗：固定宽高、标题「设置」+ 右侧随选项卡变化描述、左菜单+右内容
- 外观（浅色/深色/跟随系统）**真实生效**：`LocalDarkTheme` CompositionLocal
- 权限联动新会话默认值

---

## 4. 问题与解决方案（经验沉淀）

### 4.1 素材与资源

| 问题 | 方案 |
|---|---|
| excalidraw SVG 转 VectorDrawable 后图标完全不可用（描边过细/渲染异常，仅填充型 3.svg 与椭圆框 10.svg 正常） | 其余图标**改用 PNG 素材内置**（drawable-nodpi 防密度缩放）；写 `tools/svg2vd.py` 仅用于可用的矢量件 |
| 图标颜色在深色模式不可见 | 深色模式统一 `ColorFilter.tint` 浅色染色 |
| 素材随 APK 打包 vs 运行时外读 | 一律**内置资源**（避免存储权限与路径依赖） |

### 4.2 Compose 布局与渲染

| 问题 | 方案 |
|---|---|
| 底部元素不可见（`constraints.maxHeight` 与实际渲染高度不一致，元素被算到屏幕外） | 底部元素改用 `align(BottomStart)` + 负偏移**锚定真实边界**，不依赖高度数值 |
| 悬浮卡片遮挡消息列表、无法上滑 | 预留卡片高度 → 列表延伸到屏幕底透过空隙 → **锚点 = 与侧边栏设置行底部对齐**（普通 LazyColumn + 内容变化 scrollToItem） |
| `verticalScroll` 的 Column **不裁剪内容**，列表项绘制到容器外盖住按钮 | 容器加 `clipToBounds()` |
| 透明（alpha 0）元素仍拦截点击（覆盖下方图标/按钮） | 整层用 `AnimatedVisibility(expanded)` **移出组合树**（收纳态完全不参与命中），保留淡入淡出 |
| 弹窗/菜单层级（需在侧边栏与卡片之间） | 卡片/悬浮层为普通 composable 用 zIndex；弹窗为独立 Window 天然置顶 |
| 输入卡片高度固定，编辑器多行时挤压工具区不可见；或默认撑到屏幕一半大片空白 | 卡片 Box：`heightIn(min=140dp, max=屏半)` + Composer：`fillMaxWidth().wrapContentHeight()`（非 fillMaxSize）+ 编辑器 max 从屏1/6提升到屏1/3；卡片随内容收缩，编辑器超限后内部滚动 |
| 引导层发送失败：本地占位会话 `blank` 默认为 `false` 导致 `backendLike` 误判为 `true`，直接向后端发送到不存在的本地 ID | 本地占位会话（`__boot`、`newSession()`）设 `blank = true`，确保 `backendLike = false`，先创建真实会话再发送 |
| 内容区流式输出时滚动到用户消息气泡：`scrollToItem` 滚到助手消息顶部而非底部；布局未完成就执行滚动 | 添加底部锚点 item + `delay(50)` 等待布局稳定 + `animateScrollToItem` 平滑滚动 |
| 引导层模型列表加载：用 `session.models` 需要 sessionId，创建临时会话污染会话列表 | 改用官方 `llm.models` API 获取全局模型列表，无需 sessionId |
| 会话层滚动不跟随流式输出：每次内容变化都强制滚动，用户无法上滑查看历史 | 对齐官方 atBottom 追踪机制：`snapshotFlow` 监听滚动位置，用户上滑时停止自动滚动，显示"回到底部"按钮 |

### 4.3 组件与手势

| 问题 | 方案 |
|---|---|
| Material3 TextField 最小高度 56dp，矮容器（20dp）内文字/光标不可见 | 自绘 **BasicTextField 下划线输入框**（无最小高度，行高可控） |
| 编辑器超行后无法继续输入 | BasicTextField + `verticalScroll` + 输入时自动滚到底 |
| Compose 无 `Int*Dp`、`Dp/Int` 运算（编译期报错） | 一律 `Float` 参与（`* 2f`、`/ 2f`）——多次踩坑 |
| material3 1.2.1 `DropdownMenu` 无 `containerColor` 参数 | 反编译 aar 确认 → 改用 **Popup 自绘下拉**（背景可控） |
| foundation 1.6.8 **无 `longPressDraggable` API**（较新版本才有） | 自研 `awaitEachGesture`；后整体弃用 |
| 悬浮卡片右划收缩：需「任意速度 + 倾斜 <45°」判定 | `detectHorizontalDragGestures` 累计位移 + `abs(dy) < abs(dx)`；引导层禁手势 |
| 悬浮球展开后贴屏幕底（位置目标用了过时采样高度） | 位置目标改用**实时形态高度**（球 56 / 展开 onSizeChanged 实际高）→ 底部恒定 |

### 4.4 状态管理

| 问题 | 方案 |
|---|---|
| 流式回调多次更新会话状态时**覆盖回旧快照**（引导层发送后跳回引导层） | 更新函数读取 **map 中最新值**（latest），而非重组时捕获的 `current` 快照 |
| 编辑器文本/模型/权限随会话切换丢失或串台 | 状态提升：`Composer` 受控化（value+onChange 参数），由 `SessionState` 统一持有 |
| 主题「外观」选择不生效（组件内 `isSystemInDarkTheme()` 只读系统） | 新增 **`LocalDarkTheme` CompositionLocal**，APTUIDSHTheme 提供，全部组件统一读取 |
| 拖动回调闭包捕获旧列表导致排序回退/越界崩溃 | `rememberUpdatedState` 取最新；后整体弃用该方案 |

### 4.5 拖拽排序（最重要的教训）

**完整踩坑链**（约 9 个版本迭代）：
1. 自研 v1：`combinedClickable(onLongClick)` + 条件 `draggable` → 长按后事件衔接不可靠，行不移动
2. 自研 v2：`detectTapGestures` + `detectDragGesturesAfterLongPress` 双 pointerInput → 手势竞争，onDrag 不触发
3. 自研 v3：`awaitEachGesture` 完整自控（点击/滚动取消/500ms 长按/松手重排）→ ① `waitForUpOrCancellation` 被滚动消费误判为长按（不需长按就触发）；② 实时重排致 index 错位（跟手光标变其他标题）；③ 松手仍不排序
4. 引入开源库 **Reorderable 2.3.3**（版本经 maven POM 核实 Compose 1.6 兼容）→ 误用 `draggableHandle()`（**按下即拖，非长按**，反编译 aar 发现 scope 有 `draggableHandle`/`longPressDraggableHandle` 两个 API）
5. 改用 `longPressDraggableHandle()` → 仍异常
6. **最终决策：彻底移除**（依赖、动画、代码全清理，会话列表回归"点击切换"简单形态）

**教训**：① 手势与命中测试的坑远超预期，涉及 Compose 事件消费/竞争/闭包捕获等多层问题；② 远程无法真机调试时，迭代成本极高；③ 该功能非核心，及时止损做减法比反复试错更明智；④ 引入第三方库前应先反编译核对 API 签名（版本差异极大）。

### 4.6 构建环境

| 问题 | 方案 |
|---|---|
| aapt2 为 x86_64 无法在 arm64 Termux 运行 | `gradle.properties` 用 `android.aapt2FromMavenOverride=/data/.../usr/bin/aapt2`（Termux 原生） |
| Kotlin/Compose 首次构建下载量大、内存受限 | `org.gradle.daemon=false`、`workers.max=2`、`kotlin.daemon.jvmargs` 限内存 |
| Compose 库版本兼容 | Reorderable 选型时用 maven POM 核对 runtime 依赖（2.3.3→foundation 1.6.11 匹配我们的 1.6.8） |
| 交付文件复制后 ls 显示 0 字节 | 文件系统同步延迟，`sleep 1` 后复核 |

### 4.7 后端接入（v1.4.0，官方协议）

| 问题 | 方案 |
|---|---|
| dsh-android 参考项目**已废弃**（与官方 deepseek-harness 协议不一致） | 废弃旧参考，直接 curl 本地后端 `127.0.0.1:3081` 探测官方 JSON-RPC，以**真实返回为唯一事实源** |
| 会话真实标题不在根字段 | `session.list` 项的 `projections.values.title`（name/desc 另有 projection） |
| 用户输入的数据源在哪（v1.11.11 前误判，已修正） | 用户气泡统一从 **`user/message`** 事件（`source.kind=="user"`）的 `data.content[type=text]` 渲染，位置 = 该事件 seq（消息被 agent claim 后真正注入上下文的时刻）。`agent/inbox/spliced` 只维护 inbox 排队投影（排队列表由 `session/queue` 帧驱动），记录的是消息「入队」时刻，**不用于渲染气泡**——详见 4.15 |
| assistant 同 turn 多 step 各自发 `assistant/message` | 连续 assistant（中间只有 tool/result 等辅助事件）→ 合并 blocks 为一条消息 |
| 推理/正文/工具块混合在一条 assistant/message 的 content[] | 逐块映射：reasoning→💭 Think 卡（extra 全文）、text→正文、tool-call→工具卡；工具结果按 `toolCallId` 从 tool/result 与 user/message.tool-result 缓存回填 |
| 权限/预设等枚举需映射为中文 UI | 权限 read-only/workspace-write/danger-full-access ↔ 只读/工作区写入/完全访问；预设用 `agentPreset.list` 的 id→name |
| Kotlin 单文件解析器易与真实结构漂移 | 先用 **Python 复刻同逻辑**跑真实历史 JSON 自证（6 user + 7 assistant 共 13 条符合 52 轮事件）再落 Kotlin；events 按 `seq` 排序防乱序 |

### 4.8 会话列表分组（v1.5.0，官方 workspace 树移植）

| 问题 | 方案 |
|---|---|
| 需求侧对分组方式的认知与官方不一致（口头"用户输入/子代理/工作区三种"） | **用户裁决：以官方为准**——官方仅「按工作区 / 单列表」两档（`SessionGroupBy.WORKSPACE/FLAT`），不再自创第三档；研究结论与官方实现有出入时按官方源码落 |
| 官方实现数据源不止 session.list | 需 **`workspace.list`**（组头顺序与 members=sessionIds 存储序）+ session 项顶层的 `origin`（'subagent'）/`parentSessionId`/`running`/`blank`/`cwd`；先 curl 探测确认字段再写解析 |
| 官方可见性规则反直觉：子代理会话不占行、blank 仅当前显示 | 移植 `sessionVisible`：`origin!='subagent' && (!blank || 当前)`；子代理经父会话 lineage 呈现——Android 兜底：当前选中的子代理会话仍可见 |
| 组折叠状态与"当前组自动展开" | 派生纯函数收 `expandedKeys:Set`；`LaunchedEffect(currentId, groupBy, workspaces)` 把当前组 key 加入展开集合（对齐官方 useEffect 自动展开 current group） |
| 派生逻辑与 UI 耦合易乱 | 抽 `SessionGroups.kt` 纯函数（deriveWorkspaceGroups/deriveFlatSessions/runningSubagentCounts），UI（Sidebar）只消费结果；先 Python 复刻真实数据自证（76+1 主会话组 + 26 未分组 + 运行子代理计数） |
| 官方打包产物在本地 node_modules 可读 | 官方 web 前端包（`dsh-client-ui-workspace/lib/client.js`）与 GitHub master 源码均可当"实现方案"研读：tree.ts 分组派生、Rows.tsx 行渲染、stores.ts 持久化；手机端不持久化、组内不做 5 行溢出折叠（规划） |

### 4.9 覆盖模式与实时流式（v1.6.0，官方会话事件流移植）

| 问题 | 方案 |
|---|---|
| 侧边栏展开/收纳挤压右侧内容区变形 | 内容区与 ComposerArea **恒定从收纳态 rail 宽度右侧开始**（rail 占位不遮挡内容），不再随展开动画 `padding(start=sidebarWidth)` 让位；展开态侧边栏 z3 悬浮覆盖其上，动画只动侧边栏自身 |
| 对话层收不到模型实时输出（官方 web 能看流式） | 官方机制 = history 快照 + **events.mux 的 `session/event` 增量**；本端 ChatActivity 挂 `DshEventListener` → **串行 Channel** 按帧序消费 |
| 官方实时事件有哪些、结构如何 | 实测 mux（RFC6455 自写 probe）：`assistant/chunk`（block-start/text-delta/reasoning-delta/tool-call-delta/block-end/usage/finish）、`assistant/message`（step 权威 content）、`tool/result`、`agent/inbox/spliced`、turn/step 边界；事件带 `seq/turn/step` |
| chunk 打字机与历史渲染如何统一 | `LiveFold.kt` `applyLiveEvent`：text-delta 就地追加（streaming=true 不打印时间）、assistant/message 用权威块与同 turn 上一条合并、tool/result 按 toolCallId 回填、用户输入即时入列、turn/end 封口 |
| 帧序/重复/掉帧 | 单连接 mux 有序 → Channel 串行保序；`seq <= 尾部` 丢弃重放；**跳号触发快照重拉校准**；history 在途事件挂起待快照后重放 |
| 官方 chunk 逐字频率高（每 token 一帧） | 打字机只更新内存消息块（不重拉），落定/边界事件才可能校准；每会话独立 toolResults 缓存 |

### 4.10 输入卡片真实化（v1.7.0，官方 InputBar 移植）

| 问题 | 方案 |
|---|---|
| 发送按钮/权限/模型/推理/统计全是占位文本 | 逐项对照官方 `ui-conversation/skeleton/InputBar.tsx`（+ PermissionSelect/ContextMeter）与 `ui-model-selection` 落地真实通道 |
| 发送的真实协议 | 官方 = `session.prompt(content=[{type:text}], mode=queue)`；实测全链路：create→prompt(accepted)→`agent/inbox/spliced`(用户消息)→`assistant/message`→`turn/end`；空会话发送先 `session.create` 拿真实 id 并切换窗口，回显走已就绪的 mux 实时流 |
| 模型/推理真实来源 | `session.models`：groups[].models[].reasoning.efforts[]（off/low/high/max）；当前选择在 `current{provider,model,reasoningEffort}`；切换 = `session.selectModel(provider,model,reasoningEffort)`（实测幂等 ok） |
| 权限数据/切换 | 只读来自 `projections.permissions{options,currentValue}`（history 尾部 + mux `session/projection` 实时）；**官方切换经 `/permission` slash 命令（commands.execute），本端 rc.2 后端未暴露 commands.\* 通道**（探测 404），发成 prompt 文本会被 agent 当普通消息 → 本端先本地会话态更新，待通道可用再切命令发送 |
| 统计/上下文文本 | `projections.sessionStats`（轮次/步数/llmMs/toolMs/decodeTokens）→ 统计行；`contextPressure{pressureTokens,contextWindow}` → 百分比与 ~x / x 文本；mux `session/projection` 帧实时刷新 |
| 按钮运行态 | turn/start→running=true（发送变「停止」）；turn/end→idle；停止=`session.cancel` |
| 命令按钮 | 官方 popup 列出 host 命令目录（commands.list）→ 本端 rc.2 无该通道，命令菜单回填 `/xxx ` 到编辑器由用户发送（防误发普通消息） |

### 4.11 审批应答返回 `not-pending`（v1.7.0 修复）

**现象**：用户设置权限为 Workspace Write 后，工具请求升级沙箱权限时弹出审批对话框。无论点击「批准」还是「拒绝」，后端均返回 `not-pending` 错误，审批无法完成。

**日志**：
```
01:35:35.205 === respondToApproval START ===
01:35:35.221 outcome=allowed-once
01:35:35.225 approval.id=114dcd3e-46f9-4f45-8790-80311c6c4a2a
01:35:35.229 approval.sessionId=session-b04b0eb2-9b53-4bd1-a297-6212e54ade55
01:35:35.233 approval.muxRpcId=9a534d11-54df-49de-a51d-d72077f5d283
01:35:35.254 REQUEST payload: {"sessionId":"...","approvalId":"114dcd3e-...","outcome":"allowed-once"}
01:35:35.259 REQUEST baseUrl: http://127.0.0.1:3081/api/respond
01:35:35.759 ERROR: code=-4 msg=应答被拒绝: not-pending details={"reason":"not-pending"}
```

**根因**：`rpcId` 不匹配。后端 `pendingApprovals` Map 以 `rpcId` 为 key，客户端响应时的
`client-response` 信封 `rpcId` 必须与此一致。原代码有三处错误：

| 环节 | 原代码行为 | 正确行为 |
|---|---|---|
| `onMuxEvent` | `if (type != "session/event") return` 跳过 `approval/requested` 帧 | 应处理该帧，从信封提取正确的 rpcId |
| `approval/asked` 处理 | 用 `session/event` 帧的 rpcId（另一个随机 UUID） | 应用 `approval/requested` 帧的信封 rpcId |
| `DshClient.respond` | 每次生成新 `UUID.randomUUID()` | 应复用后端 `pendingApprovals` 的 key |

**修复方案**（3 处改动）：

**① `ChatActivity.kt` onMuxEvent** — 直接处理 `approval/requested` 帧：

```kotlin
// approval/requested 是独立 mux 帧，不是 session/event 包裹
if (type == "approval/requested") {
    val approvalEnvelopeRpcId = envelope.optString("rpcId", "")  // 后端 Map 的 key
    val approval = ApprovalRequest(
        sessionId = sid,
        muxRpcId = approvalEnvelopeRpcId,  // 必须是这个，不是 session/event 的 rpcId
        id = payload.optString("approvalId", ""),
        toolName = payload.optString("toolName", ""),
        callId = payload.optString("callId", ""),
        reason = payload.optString("reason", ""),
    )
    // ...设置 pendingApproval
    return
}
```

**② `ChatActivity.kt` liveEvents 消费** — 移除旧的 `approval/asked` 处理（避免用错误 rpcId 覆盖），仅保留 `approval/decided` 清除弹窗。

**③ `DshClient.java` respond** — 新增重载支持传入 rpcId：

```java
public void respond(final JSONObject value, final String rpcId, final DshCallback cb) {
    body.put("rpcId", rpcId != null ? rpcId : UUID.randomUUID().toString());
    // ...
}

public void respondApproval(String sessionId, String approvalId,
                            String muxRpcId, String outcome, DshCallback cb) {
    // ...
    respond(value, muxRpcId, cb);  // 复用后端 pendingApprovals 的 key
}
```

**教训**：

1. **mux 帧类型 ≠ session 事件类型**：`approval/requested` 是独立 mux 帧（`payload.type = "approval/requested"`），
   `approval/asked` 是 session 事件日志中的事件类型（通过 `session/event` 帧传输）。两者内容相关但 rpcId 不同。
2. **`client-response` 必须复用 `server-request` 的 rpcId**：标准 RPC（client-request）可自动生成 rpcId，
   但 `client-response`（对 server-request 的应答）必须复用服务端的 rpcId，否则 pending 表匹配失败。
3. **后端 Map key 语义必须严格对齐**：不能假设两个不同帧的 rpcId 相同，必须从正确的帧提取。

### 4.13 权限变更消息渲染不对齐 Web UI（v1.7.0 修复）

**现象**：APP 中权限变更显示为蓝色用户气泡，内含完整文本 `The approval policy changed from "ask" to "never" (changed by the user).`；Web UI 中显示为工具调用卡片 `🔧 permission · preset read-only`。

**根因**：

1. 权限变更时后端追加的事件序列：`command/run` → `permission/preset` → `sandbox/mode` → `command/done`
2. Web UI 的 `commandDefinition` 将 `command/run` + `command/done` 渲染为命令卡片（名称+参数+结果）
3. APP 的 `applyLiveEvent` 和 `parseSessionHistory` 均跳过了 `command/run` 和 `command/done`
4. APP 中 `user/message` 处理未过滤 `source.kind=plugin` 的系统消息，将 system-prompt snapshot（含权限变更通知文本）渲染为用户输入

**修复**：

1. `LiveFold.kt` 和 `BackendParser.kt` 增加 `command/run` + `command/done` 事件处理：
   - `command/run` → 新建 assistant 消息，含 tool-call 块（label = `name · args`，toolCallId = `cmd-{commandId}`）
   - `command/done` → 按 commandId 回填结果文本（`text` 或 ✅/❌）
2. `LiveFold.kt` 的 `applyUserMessage` 过滤 `source.kind=plugin` 的消息，避免 system-prompt snapshot 渲染为用户输入

**效果**：权限变更现在显示为灰色工具卡片 `🔧 permission · preset read-only`，与 Web UI 对齐。

### 4.14 dsh 后端 mux 帧 rpcId 语义速查

| mux 帧 payload.type | rpcId 语义 | 响应时 rpcId |
|---|---|---|
| `session/event` | 随机 UUID（帧级） | 不需要响应 |
| `session/projection` | 随机 UUID | 不需要响应 |
| `approval/requested` | **pendingApprovals Map 的 key** | **必须复用** |
| `approval/resolved` | 随机 UUID | 不需要响应 |
| `question/requested` | **pendingQuestions Map 的 key** | **必须复用** |
| `question/resolved` | 随机 UUID | 不需要响应 |

### 4.15 插话（interject/steer）消息渲染位置修复（v1.11.11）

**现象**：用户通过插话横条（`InterjectFloatingBall`，助手输出中发送）发送的消息，渲染在「发送时刻」的上下文位置（assistant 输出中间），而不是「真实注入上下文被系统收到」的位置（step 边界）。

**根因**：用户气泡的渲染数据源用错了事件。官方 Web UI 渲染用户气泡用的是 **`user/message`** 事件（`source.kind=="user"`），其 seq 是消息被 agent **claim 后真正注入上下文的时刻**（下一个 step 边界）；而 **`agent/inbox/spliced`** 只维护 inbox 排队投影，记录的是消息**「入队」时刻**（用户点发送的瞬间，此时 assistant 还在输出中）。我们 APP 的历史快照解析 `parseSessionHistory` 恰好用反了：用 `agent/inbox/spliced` 渲染用户气泡（seq 在 assistant 输出中间，位置错误），却跳过 `user/message`（`source.kind=="user"`，还误取 `data.message`——实际 user/message 的 data 就是 message 本身，导致永远为 null 而 continue）。

**官方机制（surface 层）**：这是理解关键。官方在事件日志之上维护一层 **surface**（见 `dsh-session/lib/types/surface.js`）：
- 只有三种「消息产生事件」能进 surface：`user/message` / `assistant/message` / `tool/result`，每个都带 `surfaceOp` 标记：`"append"`（追加）或 `{op:"replace", start, end}`（替换范围）。
- `foldSurface` 重放日志：append → `nodes.push(seq)`，replace → `splice(startIdx, endIdx-startIdx+1, seq)`；`nodes` 即模型可见顺序。
- 渲染节点按 `anchorSeq`（= event.seq）排序（见官方 `dsh-client-ui-conversation` 的 `orderedVisible`）。
- **所有 user/message（含 steering）的 surfaceOp 都是 `"append"`**——steering 消息由 `agent.steer()` 放入 next-step inbox，在 step 边界被 `claim` 后作为新的 `user/message` 追加到日志（见 `dsh-agent-loop` 第 577 行 `session.append("user/message", message, { surfaceOp: "append" })`），所以它的 seq 天然落在「上一步 assistant 输出之后、下一步 assistant 输出之前」，即真实注入位置。
- 官方 `messageDefinition` 只匹配 `user/message && isAppendSurfaceEvent` 渲染气泡，`agent/inbox/spliced` 由独立的 `inboxDefinition` 维护 pending/claimed 状态，二者解耦。

**修复方案**（2 个文件）：

1. **`BackendParser.kt` `parseSessionHistory`（核心）**：
   - 删除 `agent/inbox/spliced` 渲染分支（改为忽略，不渲染气泡）
   - 重写 `user/message` 分支：`source.kind=="user"` 时从 `data`（= message 本身）提取 `content[type=text]` 渲染用户气泡，`lastSeq = seq`（真实注入位置）
   - 新增 `cacheToolResult` 辅助函数（供 user/message 与 tool/result 共用缓存）

2. **`LiveFold.kt` `applyUserMessage`**：
   - 增加 `seq` 参数（由 `applyLiveEvent` 传入）
   - 新增 `findInsertIndex(messages, seq)`：按 `lastSeq` 定位插入位置（`lastSeq >= seq` 的第一条消息之前），而非一律追加末尾
   - 用户气泡与上下文注入行均按 seq 定位，并回写 `lastSeq = seq`

**关键经验**：
1. **同一消息在事件日志里有两个 seq**：「入队」seq（`agent/inbox/spliced`，发送瞬间）与「注入」seq（`user/message`，claim 后 step 边界）。渲染 transcript 必须用「注入」seq，排队列表才用「入队」seq——两者语义不同，不可混用。
2. **`user/message` 的 data 就是 message 本身**（`{id, role, source, content}`），没有嵌套 `message` 字段；`assistant/message` 的 data 才是 `{message:{...}, turn, step}`。这处差异若弄反会静默丢消息。
3. **历史快照与实时流必须用同一套渲染规则**：本次 bug 的诡异之处在于实时流（`LiveFold`）本已正确从 `user/message` 渲染，但 turn/end 后 `loadSessionHistory` 重拉快照用 `parseSessionHistory` 的错误规则覆盖了正确顺序，导致「实时看着对、刷新后就错位」。

### 4.16 排队插话按钮失效修复 + 停止时自动化清空（v1.11.12 / v1.11.13）

**现象 1（v1.11.12）**：排队插话列表中的「编辑」和「删除」按钮点击后无任何反应；「立即插入」之前有效。

**根因**：`session.updateQueue` 请求格式与官方 API 不匹配。官方 API 是**顶层 `itemId`+`action`**，我们用了嵌套 `operations` 数组。

| 字段 | 官方格式 | 修复前 APP 格式 |
|---|---|---|
| 请求体 | `{sessionId, itemId, action: {kind:"remove"}}` | `{sessionId, operations: [{action:{kind:"remove"}, itemId}]}` |

此外，`edit` 和 `steer` 操作官方是单次 API 调用（后端自动 remove + replace/steer），我们之前用「先删再发 prompt」两步操作，存在竞态风险。

**修复**：三个回调全部改为官方顶层格式，`edit` 用 `action.kind="edit"+content`，`steer` 用 `action.kind="steer"`，`remove` 用 `action.kind="remove"`，均为单次调用。

---

**现象 2（v1.11.13）**：用户手动停止流式输出后，排队插话未被清除，下一次正常输入时排队插话会依次触发。

**设计方案（纯前端自动化）**：

| 组件 | 修改内容 |
|---|---|
| `SettingsDialog` InterjectSettings | 新增「输出流终止时清空队列」开关（方形无圆角，关灰/开红），放在「启用插话」开关的第二列；✓统一改为红色 |
| `InterjectFloatingBall` | 显示条件从 `enabled && isAssistantStreaming` 扩展为 `enabled && (isAssistantStreaming \|\| userQueueItems.isNotEmpty())`；排队列表添加 `verticalScroll` 支持长列表内部滚动 |
| `ChatActivity` stopRunning() | 停止后触发自动化程序：`autoClear=true` → 遍历 queueItems 逐个调用 `session.updateQueue(kind=remove)` 清空；`autoClear=false` → 不清理，悬浮球因新显示条件常态化显示 |
| 状态管理 | 新增 `interjectAutoClear` 状态，SharedPreferences 持久化（key: `interject_auto_clear`，默认 true） |

---

## 5. 最终实现清单

| 模块 | 文件 | 功能 |
|---|---|---|
| 首屏 | MainActivity / HomeScreen / HomeViewModel | 连接状态 + host.describe 后端信息（真实接口）；整体垂直居中、文字笔画阴影、内置标题横幅 |
| 会话层消息流 | ConversationView / MessageModels | 用户直角气泡+时间+复制；助手块渲染（注入/Think/正文 markdown-lite/工具卡）；流式结束后打印时间；底部锚点 |
| 多会话窗口 | ChatActivity | SessionState Map 隔离；会话切换保存/加载；新建会话新窗口；竖屏展开遮罩；深色主题可切换 |
| 侧边栏 | Sidebar | 收纳/展开（竖 1/6↔3/5、横 1/15↔1/7 自适应）；图标移动动画；新建横幅点击；会话列表（点击切换、竖屏自动收纳、绝对高度容器滚动） |
| 统一输入卡片 | ComposerArea / Composer | 编辑器自适应+滚动；工具区（命令/权限/上下文菜单、发送、模型/推理二级菜单、统计）；右划收缩悬浮球；compact 引导层模式 |
| 内容区 | MainContent | 顶部信息栏（模式/Session log/任务/对话·轨迹选项卡）+ 消息/占位 |
| 悬浮层引导 | （ComposerArea 内） | 标题「探索未至之境」+ 工作区/预设下拉 + compact 卡片 |
| 设置弹窗 | SettingsDialog | 固定宽高；左菜单+右内容；Agent 预设卡片（°内置/自定义+描述+名字）；权限/语言/外观（外观真实切换主题） |
| 工作区目录 | WorkspacePickerDialog | 文件浏览器（面包屑/路径输入/9 行列表/新建文件夹/显示隐藏/打开取消） |
| 协议层 | net/*（Java 移植） | DshClient 53 方法 / EventStream 双 WS / DshGateway（后续接后端直接复用） |
| 后端接入 | BackendParser.kt | 解析 agentPreset.list/session.list/session.history/workspace.list（官方协议：真实用户输入来自 user/message 的 source.kind=="user"；assistant 按用户输入分隔合并；工具结果按 toolCallId 回填；会话 origin/parentSessionId/running/blank/cwd） |
| 后端会话 | ChatActivity | 启动拉取 agentPreset.list+workspace.list+session.list → 侧边栏真实会话列表；点击 → session.history 渲染真实消息流；顶行显示真实预设名（如「Android APP 开发(AG 经验)」）；**mux 实时事件订阅（串行 Channel）+ 覆盖模式** |
| 会话列表分组 | SessionGroups.kt / Sidebar | 官方 workspace 树移植：按工作区/单列表两档切换；组头折叠展开；未分组桶；子代理不占行（父行运行子代理徽标）；当前组自动展开 |
| 实时流式 | LiveFold.kt / ChatActivity | 官方 mux 会话事件流：assistant/chunk 打字机逐字、assistant/message 落定合并、tool/result 回填、用户输入即时；seq 防重放 + 跳号快照校准 |
| 输入卡片 | Composer / ComposerArea / ChatActivity | 真实发送 session.prompt；session.models 目录 + selectModel(provider/model/effort)；权限/统计/上下文投影(permissions/sessionStats/contextPressure)；停止=session.cancel |

---

## 6. 经验总结（方法论）

1. **动画与布局分离**：悬浮卡片/前两行的位移动画用 `graphicsLayer`（纯绘制不触发布局）；易变的元素各自独立容器，避免同一 Column 高度联动导致连锁重排与卡顿。
2. **命中测试与可见性无关**：alpha 0 / disabled 的元素仍可能消费指针；要"不拦截"就用条件不挂手势或 `AnimatedVisibility` 移出组合树。
3. **状态快照是隐蔽 bug 源**：异步回调（流式）里更新状态务必读最新值，勿用组合时捕获的快照。
4. **受控组件 + 状态提升**：编辑器文本、模型、权限等由上层统一持有，天然支持多会话隔离与切换动画。
5. **配色可主题化**：硬编码亮暗分支要统一走 CompositionLocal（`LocalDarkTheme`），主题切换才真实生效。
6. **手势类功能谨慎自研**：点击/长按/滚动/拖动共存时事件竞争复杂；有成熟开源库先核对版本兼容与 API 签名（反编译 aar），非核心功能不值得无限试错——及时做减法。
7. **构建环境约束先行**：Termux aapt2 override、daemon/内存限制在工程初始就配好；依赖选型用 POM 核对版本。
8. **素材内置**：避免运行时外读的存储权限与路径问题。
9. **版本演进小步快跑**：每个版本单一主题（功能/修复/优化），用户真机走查驱动迭代，无法本地目验时以自证（badging/协议 curl/代码核验）+ 用户反馈闭环。

---

## 7. 后续计划

- 接入后端：会话列表 `session.list`、消息流（`block-start → delta → block-end` 增量渲染已就绪）、host.describe 已通
- 消息增量流式替换 `demoAssistantReply`
- 会话标题从真实消息生成；相对时间随会话数据更新
- 设置持久化（SharedPreferences：主题/语言/默认权限/预设）
- 会话搜索、轨迹选项卡内容、附件等
