# aptuidsh — APTUIDSH 客户端（安卓原生）

DeepSeek Harness 的安卓原生前端 UI 客户端（会话历史真实渲染 + 文件能力）。

| 项 | 值 |
|---|---|
| 应用包名 | `com.aptuidsh.kui` |
| 应用名 | APTUIDSH |
| 版本 | versionName `1.15.0` / versionCode 94 |
| 兼容 | minSdk 24（Android 7.0）～ targetSdk 34（Android 14） |
| 技术栈 | Kotlin + Jetpack Compose (Material3) + androidx；协议层为纯 Java（无第三方依赖） |
| 后端 | dsh（本机回环 `http://127.0.0.1:3081`，未做后端修改） |
| 协议 | HTTP JSON-RPC（`POST /api/<method>`）+ 双 WebSocket 事件通道（`events.host` / `events.mux`） |
| 构建 | OpenJDK 17 + Gradle 8.13 + AGP 8.7.3 + Kotlin 2.0.21 + Compose BOM 2024.06.00 + Android SDK 34（Termux aapt2 override） |

## 架构分层

```
┌ UI 层 (Kotlin + Compose,主线程渲染)
│  MainActivity —— 网关生命周期(创建/start/onDestroy 停机) + setContent
│  HomeScreen   —— 首屏:状态条 + 品牌卡 + 后端信息卡 + 错误卡 + 通道状态行
│  HomeViewModel—— 三态 StateFlow(连接中/已连接/失败),host.describe 真实数据
│  theme        —— Material3 亮/暗主题(DeepSeek 蓝 #4D6BFE,跟随系统)
├ 协议层 (Java,自 dsh-android 移植,包 com.aptuidsh.kui.net)
│  DshClient    —— RPC 信封 {type:client-request,rpcId,method,payload},53 方法面,10s 超时
│  EventStream  —— host/mux 双 WS 通道(RFC 6455 自实现,断线指数退避重连)
│  DshGateway   —— 生命周期接线(start/shutdown/restartEvents/setBaseUrl)
│  AppRuntime   —— 跨组件网关持有器
│  AppLog       —— 全链路调试日志(Logcat + 落盘 Downloads)
└ 传输:HttpURLConnection(RPC) + 自实现 WebSocket;org.json 为 android.jar 内置
```

## 首屏数据流

`MainActivity.onCreate → AppRuntime 绑定 DshGateway(固定 http://127.0.0.1:3081) → gateway.start()
→ HomeViewModel.refresh() → DshClient.hostDescribe(后台线程) → 回调 → StateFlow → Compose 重组渲染`

`host.describe` 渲染字段：`version / provider / model / cwd / home / attachedSessions`（缺失显示 "—"）。
连接失败展示错误详情卡并出现「重试」按钮；底部实时显示 host/mux 事件通道状态。
首屏内容整体垂直居中（超高时可滚动）；文字带轻微笔画阴影（立体感）。
品牌卡标题行右侧靠齐显示透明背景标题图（内置 `drawable-nodpi/title_logo.png`，313×80）。
后端信息卡下方、事件通道上方有一行透明横幅图（内置 `drawable-nodpi/banner.png`，250×100，
靠右对齐，视觉宽度约 120dp、为第一版素材的一半），点击进入 `PlaceholderActivity`
子页面（当前仅 APTUIDSH 文本占位，无功能）。
全部素材随 APK 打包，无需存储权限。

## 对话主界面（ChatActivity，v1.1.0 新增）

由首屏横幅图点击进入（系统返回键回首屏），当前仅 UI 布局与交互，无后端接口：

- **侧边栏**（背景 `#F4FBF9`，贴边平铺 + 右缘浅色分隔线）
  - 收纳态：竖屏 屏宽/6、横屏 屏宽/15；上半屏 4 图标（菜单/新建/工作区/搜索），下半屏 2 图标（插件默认隐藏/设置）
  - 展开态：竖屏 屏宽×3/5、横屏 屏宽/7；品牌横幅 + 收纳图标(右上) + 新建横幅 + 搜索框(默认隐藏,点 6 唤醒/关闭,下移展开动画,标题行随之下移) + 会话列表标题行(5/6 小图标靠右) + 底部设置行
  - 250ms 连续过渡动画：宽度与图标 x/y/尺寸/透明度同步动画；收纳态 4 图标移动到新建横幅居中、5/6 移到标题行右侧、8 移到底部设置横幅左侧，底部文字渐显
  - 图标素材 4-9 使用 PNG（`res/drawable-nodpi/ic_side_*.png`），3/10 由 `tools/svg2vd.py` 转 VectorDrawable（`ic_side_menu.xml`/`ic_side_new_banner.xml`）
- **交互**：点击 6(搜索)图标 → 展开并聚焦会话搜索框（已实现）；3/9 图标切换收纳/展开；竖屏展开时点击侧边栏外部直接收纳，横屏不收纳；新建会话/工作区/设置为占位（暂不实现）
- **主内容区**：空状态占位「暂无会话」，会话列表区留空（后续阶段实现）

## 会话界面后端接入（v1.4.0 新增）

对话主界面（ChatActivity）启动即连真实后端：

- **agentPreset.list** → 会话显示名映射（如 `android-dev` → 「Android APP 开发(AG 经验)」）
- **session.list** → 侧边栏真实会话列表（标题取 `projections.values.title`，空会话显示引导层）
- **session.history** → 点击会话进入后，内容区渲染该会话真实消息流：
  - 用户真实输入取 `user/message` 事件（`source.kind=="user"`）的 `data.content[type=text]`（`agent/inbox/spliced` 只维护排队投影，不渲染气泡——见文末「插话消息渲染位置修复」）
  - `assistant/message` 按 step 输出，同 turn 连续助手消息合并为一组，用户消息自然分隔
  - `reasoning` → 💭 Think 卡片、`text` → 正文、`tool-call` → 工具卡（结果按 `toolCallId` 回填）
- 自动打开最近一个有历史的会话；顶部标题行显示真实预设名（`Android APP 开发(AG 经验)`）

## 会话列表分组（v1.5.0 新增，移植官方 workspace 树）

对齐官方 deepseek-harness Web UI 的会话列表分组（见官方 `packages/client/ui-workspace` 的
`tree.ts` / `WorkspaceBrowser`）：

- **两种分组方式**（标题行「工作区/会话」下拉切换）：
  - **按工作区**：组头 = Host 工作区（`workspace.list`，`home` / `deepseek-harness-android`），
    组头显示名称 + 会话数，点击折叠/展开；未入任何工作区的会话落入末尾「未分组」桶；
    当前会话所在组自动展开
  - **单列表**：全部可见会话最新优先平铺，无分组
- **官方可见性规则**：子代理会话不占普通行（83 个子代理经父会话 lineage 呈现，当前选中的
  子代理会话仍可见兜底）；空（blank）会话仅在作为当前会话时显示
- **行语义**：运行中点（当前 running 会话）、运行中的子代理后代徽标（父行显示「· n 子代理」）、
  相对时间
- 派生为纯函数：`SessionGroups.kt`（`deriveWorkspaceGroups` / `deriveFlatSessions` /
  `runningSubagentCounts`），UI 只消费结果

## 侧边栏覆盖模式 + 实时流式输出（v1.6.0）

### 覆盖模式（侧边栏不挤压内容区，收纳 rail 也不遮挡内容）

- 内容区与输入卡片**恒定从收纳态侧边栏宽度（rail）右侧开始**布局
  （`railWidth = sidebarTargetWidth(..., expanded=false)`，竖屏屏宽/6、横屏屏宽/15）——
  rail 占位不遮挡内容；不再随侧边栏展开动画挤压/重排
- **展开态**侧边栏（更宽）直接覆盖在内容区上层（z3 最顶），动画只动侧边栏自身
- 竖屏展开时内容侧有半透明遮罩（也从 rail 右侧起），点击收纳；ComposerArea 卡片不随侧边栏宽度收缩

### 实时流式输出（移植官方 mux 会话事件流）

官方 web 会话窗口 = `session.history` 快照 + `events.mux` 的 `session/event` 增量
（见官方 `dsh-client-runtime` 的 doOpen/acceptLiveEvent）。本端移植：

- **订阅**：ChatActivity 挂 `EventStream.DshEventListener`，mux 帧经**串行 Channel**
  按序消费（保证 text-delta 逐字帧序不乱）
- **折叠**：`LiveFold.kt` 的 `applyLiveEvent` 逐事件增量折叠——
  - `assistant/chunk`（text-delta/reasoning-delta）→ 打字机就地追加（流式中不打印时间）
  - `assistant/message` → step 权威落定，与同 turn 上一条 assistant 合并
  - `tool/result` → 按 `toolCallId` 精确回填工具卡结果
  - `agent/inbox/spliced`/`user/message` → 用户输入实时入列
  - `turn/end`/`step/end` → 封口流式（打印时间行）
- **seq 语义**：history 快照记录尾部 seq；增量事件 `seq > 尾部` 才应用；
  **跳号（掉帧/重连缺口）自动快照校准**；history 在途事件挂起、快照完成后重放
- 仅已打开（加载过历史）的会话接收实时增量；每会话独立 toolResults 缓存

## 输入卡片真实化（v1.7.0，移植官方 InputBar/Composer 语义）

底部悬浮卡片全部功能接真实后端（对照官方 `ui-conversation/skeleton/InputBar.tsx`、
`PermissionSelect.tsx`、`ContextMeter.tsx` 与 `ui-model-selection`）：

- **发送按钮**：真实 `session.prompt(mode=queue)`（实测 create→prompt→
  `agent/inbox/spliced`→`assistant/message`→`turn/end` 全链路）；空文本禁用；
  turn/start 后按钮变「停止」（`session.cancel`）；用户消息/回复经 mux 实时流回显
- **命令按钮**：命令菜单选择填入编辑器（官方 popup 语义；命令执行需后端
  `commands.*` 通道，rc.2 未暴露，故本端回填由用户确认发送）
- **权限按钮**：真实选项来自 `projections.permissions`（history 快照 + mux
  `session/projection` 实时回推）；选择更新会话权限态（官方 `/permission` slash
  命令需后端命令通道，待通道可用即切换为命令发送）
- **模型/推理**：`session.models` 真实目录（provider 分组/模型/off-low-high-max
  档位）；切换 = `session.selectModel(provider, model, reasoningEffort)`（实测幂等生效）
- **实时统计文本**：`projections.sessionStats`（轮次/步数/LLM 与工具耗时/输出 token）
- **上下文图标**：`projections.contextPressure` 真实百分比与用量（~539K / 1M）
- 数据更新链路：history 快照尾部 projections 初始化 + mux `session/projection`
  帧实时刷新（permissions/sessionStats/contextPressure）

## 输入卡片高度自适应（v1.7.1 修复）

底部悬浮卡片编辑器高度自适应修复：

- **问题**：输入多行文字时，卡片高度固定不变，挤压工具区（图标/按钮/模型行/统计行）不可见；
  或卡片默认撑到屏幕一半，大片空白
- **根因**：卡片 Box 使用固定 `height(animCardH)` + Composer 使用 `fillMaxSize()` 强制填满父容器
- **修复**：
  - 卡片 Box：`heightIn(min = 140.dp, max = screenH * 0.5f)` — 最小140dp（3行+工具区），最大屏幕一半
  - Composer：`fillMaxWidth().wrapContentHeight()` — 高度随内容收缩，不再强制填满
  - 编辑器：`editorMax = screenH / 3f` — 编辑器最大扩展到屏幕1/3，超出后内部滚动
- **效果**：默认显示3行文字；输入增多时卡片向上扩展；达到屏幕一半后编辑器内部滚动，工具区始终可见

## 引导层真实后端接入（v1.8.1）

新会话引导层（Welcome Hero）接入真实后端数据，移除所有占位数据：

### 工作区选择（参考官方 `connectWorkspace` 语义）

- 数据来源：`workspace.list` RPC → `WorkspaceEntry(workspaceId, path, title, sessionIds)`
- 选择逻辑（对齐官方 `connectWorkspace`）：
  1. 查找可复用的空白会话（同 workspace、blank、未归档）
  2. 若有 → 直接切换到该会话
  3. 若无 → `session.create({ workspaceId, agentPreset? })` 创建新会话
- UI：下拉菜单显示工作区名称+路径，选中项标 ✓

### Agent 预设选择（参考官方 `AgentPresetSeat` Staging 语义）

- 数据来源：`agentPreset.list` RPC → `AgentPresetEntry(id, name, description)`
- 选择逻辑（对齐官方 Staging 机制）：
  1. 选择预设 → 暂存（staged）
  2. 若当前有空白会话 → 立即 `agentPreset.select(sessionId, agentPreset)` 应用
  3. 若无空白会话 → 暂存，等 `session.create` 时通过 `agentPreset` 参数传入
- UI：下拉菜单显示预设名称+描述，选中项标 ✓

### 数据流

```
启动 → agentPreset.list → parsePresetList → presetList
     → workspace.list → parseWorkspaceList → workspaces
     → session.list → 会话列表

用户选择工作区 → pickWorkspace(workspaceId)
  → 查找可复用空白会话 / session.create({ workspaceId, agentPreset })
  → 切换到新会话

用户选择预设 → pickPreset(presetId)
  → 暂存 selectedPresetId
  → 若有空白会话 → agentPreset.select(sessionId, agentPreset)

用户发送消息 → sendPromptToSession(text)
  → session.create({ workspaceId, agentPreset }) + session.prompt(content)
```

## 审批权限弹窗（v1.7.0 关键功能）

当后端工具需要升级沙箱权限（如 workspace-write → danger-full-access）时，
客户端弹出审批对话框供用户批准或拒绝。

### 协议流程

```
后端工具请求升级权限
  ↓
后端生成 pendingApproval { rpcId=<UUID-A>, approvalId=<UUID-B>, ... }
  ↓
后端存入 pendingApprovals Map（key = rpcId=<UUID-A>）
  ↓
后端发送 approval/requested mux 帧（信封 rpcId=<UUID-A>）
  ↓
后端发送 session/event 内 approval/asked 事件（data.id=<UUID-B>）
  ↓
客户端接收 approval/requested 帧 → 弹出审批对话框（保存 rpcId=<UUID-A>）
  ↓
用户点击「批准」/「拒绝」
  ↓
客户端 POST /api/respond（client-response 信封 rpcId=<UUID-A>）
  ↓
后端通过 rpcId=<UUID-A> 匹配 pendingApprovals → 审批完成
```

### 关键：rpcId 复用

- `approval/requested` 帧的**信封 rpcId** 是后端 `pendingApprovals` Map 的 key
- 客户端响应时**必须复用此 rpcId**，否则后端返回 `not-pending`
- `session/event` 帧的 rpcId 是另一个随机 UUID，不能用于响应

---

## 构建

```bash
export ANDROID_HOME=/data/data/com.termux/files/home/android-sdk
export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk
cd /data/data/com.termux/files/home/deepseek-harness-android/aptuidsh
/data/data/com.termux/files/home/gradle-8.13/bin/gradle assembleDebug --no-daemon
# 产物:app/build/outputs/apk/debug/app-debug.apk
```

## 后续阶段(规划)

- 组内 5 行折叠「展开其余 n 个」溢出控制（官方 collapsedSessionRows；当前展开组全量列出）
- 工作区 / 子代理 / jobs / 设置(基址可配置)等
- 主题切换入口(亮/暗/跟随系统)
- 提问（question/requested）应答弹窗

## Bug 修复（v1.8.3）

### 引导层发送失败：本地会话被误判为后端会话

**问题**：在引导层输入文本点击发送后，页面停留在引导层，输入没有被送到后端。必须先切换工作区再发送才能正常工作。

**根因**：`sendPromptToSession` 中的 `backendLike` 判断逻辑有误。`__boot` 和 `newSession()` 创建的本地占位会话的 `blank` 字段默认为 `false`，导致 `backendLike = true`。代码直接向后端发送消息到一个不存在的本地会话 ID，而非先创建真实会话。

**修复**：将本地占位会话的 `blank` 字段设为 `true`：

```kotlin
// __boot 会话
SessionState(id = "__boot", showWelcome = true, blank = true)

// newSession() 创建的会话
SessionState(..., blank = true)  // 本地占位会话,非后端真实会话
```

**修复后流程**：
1. 用户在引导层输入文本并发送
2. `sendPromptToSession` 检测到 `blank = true` → `backendLike = false`
3. 先调用 `session.create` 创建真实后端会话
4. 再调用 `session.prompt` 发送消息
5. 页面从引导层切换到会话层

### 内容区滚动定位错误：流式输出时跳到用户消息气泡

**问题**：
1. 重启APP后进入会话，内容区定位在用户最后一次输入的蓝色气泡位置，而非最新输出行
2. 流式输出时，内容区老是自动跳到用户消息气泡位置

**根因**：
1. `scrollToItem(messages.size - 1)` 滚动到的是助手消息的**顶部**，如果助手消息高度大于视口，用户消息会显示在上方
2. `LaunchedEffect(messages)` 在 LazyColumn 布局完成前就执行滚动，导致位置不准
3. 流式输出时，assistant 消息就地更新（size 不变），但 `scrollToItem` 每次都重新执行，可能干扰滚动位置

**修复**：
1. 在 LazyColumn 末尾添加底部锚点 item，滚动到 `messages.size`（锚点位置）确保最后一条消息底部完整显示
2. 使用 `LaunchedEffect(messages.size, blocksSize)` 监听变化，添加 `delay(50)` 等待布局稳定
3. 使用 `animateScrollToItem` 平滑滚动，配合 `try-catch` 防止并发异常

```kotlin
// 底部锚点 item
item(key = bottomAnchorKey) {
    Spacer(Modifier.height(4.dp))
}

// 滚动逻辑
LaunchedEffect(messages.size, messages.lastOrNull()?.blocks?.size) {
    if (messages.isNotEmpty()) {
        kotlinx.coroutines.delay(50) // 等待布局完成
        listState.animateScrollToItem(messages.size) // 滚到锚点
    }
}
```

## 会话层流式输出对齐官方（v1.8.4）

对齐官方 DeepSeek Harness Web UI 的会话层流式输出渲染和交互机制：

### 滚动机制（对齐官方 ChatView）

| 官方机制 | 实现方式 |
|---------|---------|
| `atBottomRef` 追踪 | `snapshotFlow` 监听 LazyListState，阈值 50px 判断是否在底部 |
| 内容变化自动跟随 | `LaunchedEffect(messages.size, blocksSize)` + `atBottom` 条件 |
| 用户滚动中断 | `atBottom = false` 时停止自动滚动 |
| "回到底部"按钮 | `AnimatedVisibility(!atBottom)` 显示悬浮按钮 |

### TurnStatus 流式状态指示器

对齐官方 `TurnStatus` 组件：
- 流式输出时显示 "Deep diving…" 动画（圆点脉冲）
- 15 秒后显示计时器（如 "2m 30s"）
- 位于消息列表底部，随内容滚动

### 消息渲染优化

- 助手消息流式中无内容块时显示"思考中…"加载指示器
- 底部锚点 item 确保最后一条消息完整显示

## 侧边栏搜索会话（v1.8.5）

对齐官方 `deriveSearchResults` 实现客户端搜索：

### 搜索机制

| 官方实现 | 我们的实现 |
|---------|-----------|
| `deriveSearchResults` 过滤会话标题/工作区 | `remember(searchText, sessions, workspaces)` 派生搜索结果 |
| 搜索框在侧边栏标题行右侧 | 搜索框在侧边栏展开态，点击搜索图标激活 |
| 结果显示标题+工作区+内容摘要 | 结果显示标题（高亮匹配）+ 相对时间 |
| 无结果时显示提示 | 无结果时显示"未找到匹配的会话" |

### 搜索逻辑

```kotlin
// 客户端过滤:会话标题/预设名/工作区名
val searchResults = sessions.values.filter { s ->
    s.title.lowercase().contains(query) ||
    s.agentPresetName.lowercase().contains(query) ||
    workspaceTitle.lowercase().contains(query)
}.sortedByDescending { it.updatedAt }.take(20)
```

### UI 交互

- 点击搜索图标 → 搜索框展开并聚焦
- 输入文字 → 实时过滤会话列表
- 匹配文字高亮显示（蓝色加粗）
- 点击清除按钮 → 清空搜索
- 点击搜索结果 → 切换到该会话

## 设置项真实应用（v1.8.6）

对齐官方设置实现，Agent 预设和权限设置项现在能够真实工作：

### Agent 预设设置

| 官方实现 | 我们的实现 |
|---------|-----------|
| `settings.update ns=agent-presets patch={default: presetId}` | 同样调用 `settings.update` |
| 新会话自动使用 `agent-presets.default` | 新会话传入 `settingsAgentPreset` |

### 权限设置

| 官方实现 | 我们的实现 |
|---------|-----------|
| `settings.update ns=permission patch={defaultPreset: value}` | 同样调用 `settings.update` |
| 新会话默认权限 = `permission.defaultPreset` | 新会话传入 `settingsPermission` |

### 设置加载

启动时从后端加载设置：
```kotlin
// settings.describe → 解析 agent-presets.default 和 permission.defaultPreset
val settingsValue = suspendRpc(gateway, "settings.describe", JSONObject())
```

### 设置持久化

用户修改设置时立即持久化到后端：
```kotlin
// Agent 预设
val patch = JSONObject().put("default", preset)
val payload = JSONObject().put("ns", "agent-presets").put("patch", patch)
suspendRpc(gateway, "settings.update", payload)

// 权限
val patch = JSONObject().put("defaultPreset", backendValue)
val payload = JSONObject().put("ns", "permission").put("patch", patch)
suspendRpc(gateway, "settings.update", payload)
```

## Bug 修复（v1.8.7）

### 新会话模型列表未加载

**问题**：新开会话时，模型列表为空，必须切换工作区才能获取模型列表。

**根因**：`newSession()` 创建会话时，`defaultModels` 可能为空（启动时异步加载未完成）。

**修复**：在 `newSession()` 中检测 `defaultModels` 是否为空，如果为空则异步加载：
```kotlin
if (defaultModels.isEmpty()) {
    scope.launch {
        val modelsValue = suspendRpc(gateway, "llm.models", JSONObject())
        // ...加载模型列表
        // 更新当前会话的模型目录
        sessionModelCatalog = sessionModelCatalog.toMutableMap().apply { put(newId, models) }
    }
}
```

### 流式输出内容跳到顶部

**问题**：流式输出时，内容偶尔跳到输出的顶部位置。

**根因**：`atBottom` 状态在动画期间被错误更新。当 `animateScrollToItem` 执行时，滚动位置变化触发 `snapshotFlow`，可能将 `atBottom` 设为 false。

**修复**：
1. 添加 `isFollowing` 标志，动画期间不更新 `atBottom`
2. 改进底部检测逻辑：检查最后一个 item 是否是列表末尾
3. 动画完成后重新检查 `atBottom` 状态

```kotlin
var isFollowing by remember { mutableStateOf(false) }

// 动画期间不更新 atBottom
LaunchedEffect(messages.size, messages.lastOrNull()?.blocks?.size) {
    if (messages.isNotEmpty() && atBottom) {
        isFollowing = true
        // ...执行滚动动画
        isFollowing = false
        // 动画完成后重新检查
    }
}
```

## 自动化程序：设置项真实应用（v1.8.8）

重构设置项应用机制，使用「自动化程序」方案替代原有实现：

### 原有方案问题

1. 设置项通过 `newSession()` / `sendPromptToSession()` / `pickWorkspace()` 参数传递
2. 设置可能在启动时未加载完成，导致新会话使用默认值
3. 设置修改后未重新读取后端配置，无法确认保存成功

### 新方案：自动化程序

**核心思路**：设置项不再通过会话创建参数传递，而是由自动化程序在会话创建后自动应用。

#### 工作流程

```
APP 启动 → 从后端加载设置（settings.describe）
         → 预加载 Agent 预设列表（agentPreset.list）
         → 预加载权限选项（从 settings.describe）

用户修改设置 → 更新本地状态
             → 发送到后端（settings.update）
             → 重新读取后端配置（确认保存成功）

用户点击新开会话 → 创建本地会话
               → 触发自动化程序 applySettingsToNewSession(sessionId)
               → 自动化程序：
                  1. 从后端重新拉取最新设置配置
                  2. 应用 Agent 预设（agentPreset.select）
                  3. 应用权限（更新会话状态）
                  4. 更新本地会话状态，UI 渲染出正确的预设和权限
```

#### 关键代码

```kotlin
fun applySettingsToNewSession(sessionId: String) {
    val gateway = AppRuntime.gateway() ?: return
    scope.launch {
        // 1. 从后端重新拉取最新设置配置
        val settingsValue = suspendRpc(gateway, "settings.describe", JSONObject())
        // ...解析最新预设和权限

        // 2. 应用 Agent 预设（对齐官方 agentPreset.select）
        gateway.client().agentPresetSelect(sessionId, latestPresetId, ...)

        // 3. 应用权限（更新会话状态）
        sessions = sessions.toMutableMap().apply {
            val s = this[sessionId] ?: return@apply
            put(sessionId, s.copy(permission = latestPermission))
        }
    }
}
```

#### 移除的旧逻辑

- `newSession()` 中移除 `settingsPermission` 和 `settingsAgentPreset` 参数
- `sendPromptToSession()` 中移除 `selectedPresetId` 和 `settingsPermission` 参数
- `pickWorkspace()` 中移除 `selectedPresetId` 参数

## Bug 修复（v1.8.9）

### 问题1：自动化程序未正确应用 Agent 预设

**根因**：
1. 对于本地占位会话（`blank = true`），`agentPreset.select` 无法调用（会话不存在于后端）
2. 对于真实后端会话，`agentPreset.select` 可能在会话初始化完成前调用

**修复**：
1. 本地占位会话：只更新本地状态，预设会在会话创建到后端时应用（`sendPromptToSession` 或 `pickWorkspace`）
2. 真实后端会话：延迟 500ms 等待会话完全初始化后再调用 `agentPreset.select`
3. `sendPromptToSession`：保存本地占位会话的预设 ID，创建后端会话后立即调用 `agentPreset.select`

```kotlin
// 本地占位会话:只更新本地状态
if (isLocalPlaceholder) {
    sessions = sessions.toMutableMap().apply {
        put(sessionId, s.copy(agentPresetId = latestPresetId, ...))
    }
} else {
    // 真实后端会话:延迟后调用 agentPreset.select
    kotlinx.coroutines.delay(500)
    gateway.client().agentPresetSelect(sessionId, latestPresetId, ...)
}
```

### 问题2：权限设置 UI 渲染错误

**根因**：`permission` 变量存储的是显示名（如 "Workspace Write"），但 `OptionList` 比较的是后端值（如 "workspace-write"）。

**修复**：在传递给 `OptionList` 前，将显示名映射为后端值：

```kotlin
val permBackendValue = when (permission) {
    "Read Only" -> "read-only"
    "Workspace Write" -> "workspace-write"
    "Full access" -> "danger-full-access"
    else -> permission
}
OptionList(options = permOptions, selected = permBackendValue, ...)
```

### 保留用户手动选择功能

用户仍然可以在引导层手动选择：
- 工作区（下拉菜单选择）
- Agent 预设（下拉菜单选择）
- 权限（ComposerArea 图标按钮）
- 模型（ComposerArea 模型选择器）

手动选择会覆盖设置的默认值。

## 移除本地会话设计（v1.9.0）

### 问题根因

之前的设计在前端创建本地占位会话（`blank = true`），导致：
1. `agentPreset.select` 无法调用（会话不存在于后端）
2. 设置无法真实应用
3. 各种 workaround 和 bug

### 官方流程

```
用户点击新开会话 → 后端立即创建空白会话 → 返回真实 sessionId → 前端使用该 ID
```

### 修复方案

1. **移除 `__boot` 本地占位会话**：初始 sessions 为空，启动时自动创建真实后端会话
2. **`newSession()` 改为真实创建**：调用 `session.create` 获取真实 sessionId
3. **`sendPromptToSession()` 简化**：移除本地会话检测逻辑，所有会话都是真实后端会话
4. **`applySettingsToNewSession()` 简化**：移除本地占位会话检测，所有会话都可直接调用 `agentPreset.select`

### 新流程

```
APP 启动 → session.list 检查
  ├─ 有会话 → 使用已有会话
  └─ 无会话 → session.create 创建真实会话 → applySettingsToNewSession

用户点击新开会话 → newSession()
  → session.create 创建真实后端会话
  → 获取真实 sessionId
  → 加载模型目录
  → applySettingsToNewSession(sessionId)
    → settings.describe 拉取最新配置
    → agentPreset.select 应用预设
    → 更新本地权限状态
```

### 移除的代码

- `__boot` 本地占位会话初始化
- `sendPromptToSession` 中的 `backendLike` 检测和本地会话创建逻辑
- `applySettingsToNewSession` 中的 `isLocalPlaceholder` 检测

## Bug 修复：引导层预设显示不同步（v1.9.1）

### 问题

设置默认预设为"极简模式"后，新开会话时引导层第二行右侧显示"标准模式"，但实际发送后应用的模式是正确的"极简模式"。

### 根因

代码中有两个独立的预设状态变量没有同步：

| 变量 | 用途 | 更新时机 |
|------|------|---------|
| `selectedPresetId` | **引导层显示** | 仅在启动时和用户手动选择时更新 |
| `settingsAgentPreset` | **设置弹窗显示** | 在 `applySettingsToNewSession` 中更新 |

`applySettingsToNewSession` 更新了 `settingsAgentPreset`，但没有更新 `selectedPresetId`，导致引导层显示的是旧值。

### 修复

在所有更新 `settingsAgentPreset` 的地方，同步更新 `selectedPresetId`：

```kotlin
// applySettingsToNewSession 中
onSettingsAgentPresetChange(defaultPreset)
selectedPresetId = defaultPreset  // ← 同步引导层显示

// SettingsDialog 回调中
onSettingsAgentPresetChange(preset)
selectedPresetId = preset  // ← 同步引导层显示

// 启动时加载设置
onSettingsAgentPresetChange(defaultPreset)
selectedPresetId = defaultPreset  // ← 同步引导层显示
```

## 侧边栏模型配置入口（v1.11.1）

在侧边栏搜索图标下方新增「素材」图标按钮，点击弹出模型配置弹窗。

### 弹窗内容（两层结构）

**首页：已配置的提供方**
- 只显示已配置 API 密钥的提供方（`credential.configured == true`）
- 每行显示提供方名称 + "已配置 ✓" 状态
- 右侧「编辑」按钮（可修改密钥）+ 「删除」按钮（非内置提供方）
- 底部「添加提供方」按钮 → 进入添加页

**添加页：所有未配置的提供方**
- 显示所有未配置的提供方（`credential.configured != true`）
- 每行显示提供方名称 + "未配置" 状态
- 右侧「编辑」按钮 → 弹出 API 密钥配置对话框
- 左上角「←」返回按钮 → 返回首页

### 技术实现

| 功能 | 官方实现 | 我们的实现 |
|------|---------|-----------|
| 读取提供方列表 | `llm.providers` RPC | `llm.providers` RPC（对齐官方） |
| 读取凭据状态 | `credentials.describe` RPC | `credentials.describe` RPC（对齐官方） |
| 保存提供方配置 | `settings.mutate` + `credentials.set` | `settings.mutate` + `credentials.set`（对齐官方） |
| 删除提供方 | `settings.mutate` + `credentials.unset` | `settings.mutate` + `credentials.unset`（对齐官方） |

### 新增文件

- `MaterialDialog.kt`：模型配置弹窗 UI（两层结构：已配置列表 / 添加页）+ API 密钥编辑对话框
- `ic_side_material.png`：侧边栏素材图标（来自 `/storage/emulated/0/Download/res/21.png`）

## 模型配置弹窗完善（v1.11.9）

### 新增功能

1. **API 地址占位符**：编辑提供方时，显示默认的 API 地址占位
   - 有 `baseURL` 字段：显示 `默认: https://...`
   - 无 `baseURL` 字段：显示 `提供方默认`

2. **已配置模型列表**：编辑提供方时，显示该提供方已配置的模型列表
   - 从 `settings.describe` 返回数据中提取 `models` 字段
   - 显示模型名称和 ID

### 技术实现

| 功能 | 数据来源 | 说明 |
|------|---------|------|
| API 地址占位 | `settings.describe` → `providers.<id>.baseURL` | 从后端配置中提取 |
| 已配置模型列表 | `settings.describe` → `providers.<id>.models` | 从后端配置中提取 |

### 遇到的问题与解决

**问题1：API 地址占位不显示**

原因：使用 `llm.providers` 接口获取提供方列表，但该接口不返回 `baseURL` 字段。

解决：同时调用 `settings.describe` 接口获取完整配置，从中提取 `baseURL`。

**问题2：已配置模型列表不显示**

原因：ProviderEditDialog 中缺少显示 `provider.models` 的代码。

解决：在 ProviderEditDialog 中添加已配置模型列表显示逻辑。

**问题3：xiaomi 没有显示"提供方默认"占位**

原因：占位逻辑错误，当 `baseURL` 为空时显示"输入自定义 API 地址"。

解决：修改为显示"提供方默认"。

### 数据流

```
1. llm.providers → 获取提供方列表（provider, displayName, settingsNs, settingsPath）
2. settings.describe → 获取完整配置（baseURL, models, apiKeyEnv 等）
3. credentials.describe → 获取凭据状态（configured, writable）
4. 合并 → ProviderEntry（包含 defaultBaseURL 和 models）
```

## 插话消息渲染位置修复（v1.11.11）

### 问题现象

用户通过插话横条（`InterjectFloatingBall`，助手输出中发送）发送的消息，渲染在「发送时刻」的
上下文位置（assistant 输出中间），而不是「真实注入上下文被系统收到」的位置（step 边界）。

### 根因：用户气泡的渲染数据源用错了事件

官方 Web UI 渲染用户气泡用的是 **`user/message`** 事件（`source.kind=="user"`），其 seq 是消息被
agent **claim 后真正注入上下文的时刻**（下一个 step 边界）；而 **`agent/inbox/spliced`** 只维护
inbox 排队投影，记录的是消息**「入队」时刻**（用户点发送的瞬间，assistant 还在输出中）。

我们 APP 的历史快照解析 `parseSessionHistory` 恰好用反了：

| 事件 | 官方正确做法 | 修复前 APP 的做法 |
|---|---|---|
| `user/message`（source.kind=="user"） | ✅ 渲染用户气泡（位置=注入 seq） | ❌ 跳过（且误取 `data.message`，实际 data 就是 message，永远为 null） |
| `agent/inbox/spliced` | ❌ 不渲染（只维护队列投影） | ❌ 用它渲染用户气泡（位置=入队 seq，错误） |

**后果**：插话消息渲染在「发送时刻」（入队 seq，位于 assistant 输出中间），而非「真实注入位置」
（user/message 的 seq，位于 step 边界）。且该错误在 **turn/end 后重拉历史快照**时发生——历史解析
用错误顺序覆盖了实时流中本来的正确顺序（实时流 `LiveFold` 本就从 `user/message` 渲染）。

### 官方 surface 层机制（理解关键）

官方在事件日志之上维护一层 **surface**（见 `dsh-session/lib/types/surface.js`）：

- 只有三种「消息产生事件」能进 surface：`user/message` / `assistant/message` / `tool/result`，
  每个带 `surfaceOp` 标记：`"append"`（追加）或 `{op:"replace", start, end}`（替换范围）
- `foldSurface` 重放日志：append → `nodes.push(seq)`，replace → `splice(...)`；`nodes` 即模型可见顺序
- 渲染节点按 `anchorSeq`（= event.seq）排序（官方 `dsh-client-ui-conversation` 的 `orderedVisible`）
- **所有 user/message（含 steering）的 surfaceOp 都是 `"append"`**：steering 消息由 `agent.steer()`
  放入 next-step inbox，在 step 边界被 `claim` 后作为新的 `user/message` 追加到日志
  （`dsh-agent-loop`：`session.append("user/message", message, { surfaceOp: "append" })`），
  所以其 seq 天然落在「上一步 assistant 输出之后、下一步 assistant 输出之前」
- 官方 `messageDefinition` 只匹配 `user/message && isAppendSurfaceEvent` 渲染气泡；
  `agent/inbox/spliced` 由独立的 `inboxDefinition` 维护 pending/claimed 状态，二者解耦

### 修复方案

1. **`BackendParser.kt` `parseSessionHistory`（核心）**
   - 删除 `agent/inbox/spliced` 渲染分支（改为忽略，不渲染气泡）
   - 重写 `user/message` 分支：`source.kind=="user"` 时从 `data`（= message 本身）提取
     `content[type=text]` 渲染用户气泡，`lastSeq = seq`（真实注入位置）
   - 新增 `cacheToolResult` 辅助函数

2. **`LiveFold.kt` `applyUserMessage`**
   - 增加 `seq` 参数（由 `applyLiveEvent` 传入）
   - 新增 `findInsertIndex(messages, seq)`：按 `lastSeq` 定位插入位置（`lastSeq >= seq` 的
     第一条消息之前），而非一律追加末尾
   - 用户气泡与上下文注入行均按 seq 定位，并回写 `lastSeq = seq`

### 关键经验

1. **同一消息在事件日志里有两个 seq**：「入队」seq（`agent/inbox/spliced`，发送瞬间）与
   「注入」seq（`user/message`，claim 后 step 边界）。渲染 transcript 必须用「注入」seq，
   排队列表才用「入队」seq——两者语义不同，不可混用。
2. **`user/message` 的 data 就是 message 本身**（`{id, role, source, content}`），没有嵌套
   `message` 字段；`assistant/message` 的 data 才是 `{message:{...}, turn, step}`。弄反会静默丢消息。
3. **历史快照与实时流必须用同一套渲染规则**：实时流本已正确，但历史重拉用错误规则覆盖，
   导致「实时看着对、刷新后就错位」。

## 排队插话按钮修复 + 停止时自动化清空（v1.11.12 / v1.11.13）

### session.updateQueue 请求格式修复（v1.11.12）

**问题**：排队插话列表中的「编辑」和「删除」按钮点击无反应。

**根因**：请求格式与官方 API 不匹配，后端解析失败返回错误，`suspendRpc` 静默吞错。

| 字段 | 官方格式 | 修复前 |
|---|---|---|
| 请求体 | `{sessionId, itemId, action: {kind:"remove"}}` | `{sessionId, operations: [{action, itemId}]}` |

**修复**：三个按钮（编辑/删除/立即插入）全部改为官方顶层格式：
- **删除**：`session.updateQueue { itemId, action: { kind: "remove" } }`
- **编辑**：`session.updateQueue { itemId, action: { kind: "edit", content: [...] } }`（后端原生替换，无需先删再发）
- **立即插入**：`session.updateQueue { itemId, action: { kind: "steer" } }`（后端原生 remove + steer）

### 停止时自动化清空（v1.11.13）

**问题**：用户手动停止输出后，排队插话未清除，下次正常输入时排队插话依次触发。

**解决方案**：在设置「插话」项新增「输出流终止时清空队列」开关。

| 自动清空 ON | 自动清空 OFF |
|---|---|
| 停止后自动逐个删除排队中的用户消息 | 不清理，悬浮球常态化显示排队列表供手动操作 |

**实现要点**：
- **悬浮球显示条件**：从 `enabled && isAssistantStreaming` 扩展为
  `enabled && (isAssistantStreaming || userQueueItems.isNotEmpty())`
- **stopRunning() 自动化程序**：停止后检查 queueItems，autoClear=ON 时逐个调用
  `session.updateQueue(kind=remove)` 清空
- **排队列表**：添加 `verticalScroll` 支持长列表内部滚动
- **状态持久化**：`interjectAutoClear` → SharedPreferences `interject_auto_clear`

### UI 优化（v1.11.13）
- 方形无圆角开关：透明背景，关闭灰色边框，开启红色边框+红色填充
- 排队列表选项 ✓ 统一红色（与 DangerRed 一致）

## 文件能力（v1.14.2）：路径识别 / 类型识别 / 可点击路径 / APK 安装 / 内置文件浏览器

对齐参考客户端 dsh-client-app 的五大文件能力，但**仅借鉴工作流与交互语义，代码全部为本端
自行实现**（Compose + Kotlin,零新依赖）：

### 1. 路径识别 + 文件类型识别（`file/FileOpenKit.kt`）

- **路径识别**：对聊天正文跑「前缀(绝对路径/别名) + 已知扩展名」正则
  （`~/`、`/storage/emulated/0`、`/sdcard`、`/data/…`、`Download[s]/`），
  反引号行内代码容器、✅ 说明里的路径同样命中（渲染时先剥反引号再识别，偏移对齐）。
- **别名解析**：`~/`、`Download/`、`/sdcard`、Termux `storage/` 软链目录 → 真实绝对路径。
- **类型识别**：扩展名 → `Kind`（图片/视频/音频/APK/文本/源码/压缩包/PDF/其他），
  驱动「内置查看 / 系统安装 / ACTION_VIEW」分流与文件浏览器图标。

### 2. 路径可点击（消息渲染层，`ui/ConversationView.kt`）

- `LinkifyAnnotated`：保留既有 markdown-lite 行内样式（加粗/行内代码灰底），把命中路径
  叠加为「**灰色背景 + 红色文字**」的可点击样式，用 `ClickableText` 按点击偏移命中路径段。
- 用户气泡、助手正文（含代码围栏块内路径）均接入；点击统一走分发器。

### 3. 打开分发工作流（点击路径 → 判断目录/文件 → 打开）

```
点击路径
  ├─ 已知文件扩展名 → openFile(路径)
  │     ├─ 域判断:SHARED(共享) / APP(本应用) / TERMUX(私有) / OTHER
  │     ├─ TERMUX/OTHER → 引导提示(复制路径, 让助手复制到 Downloads)
  │     ├─ 图片 → 内置图片查看(MediaStore URI 兜底, 免全部文件权限)
  │     ├─ 文本/源码 → 内置文本查看(≤2MB 截断提示; 未授权一键去授权)
  │     ├─ APK → 安装唤醒(见下)
  │     ├─ 音/视频 → 内置播放器 PlayerActivity(MediaStore URI 免权限;不调系统播放器)
  │     └─ PDF/压缩/未知 → FileServeProvider content URI + ACTION_VIEW
  └─ 无扩展名 → 后端 host.listDirectory 探测
        ├─ 可列出 → 视为目录 → 打开文件浏览器定位到该目录
        └─ 失败   → 按文件打开(同上)
```

### 3.1 内置音/视频播放器（`PlayerActivity`，v1.14.1）

- 视频：`VideoView` + `MediaController`（播放/暂停/进度/全屏由系统控件提供）；
- 音频：`MediaPlayer(prepareAsync)` + 进度条/时间/播放暂停/关闭，500ms 心跳刷新进度；
- 数据源：优先直读本地文件路径，未授权/未入库时用 MediaStore content URI；
- 不依赖任何系统播放应用（对齐参考端「内置查看/播放」语义）。

### 4. APK 文件安装唤醒（`FileOpenKit.installApk` + `InstallReceiver`）

```
点击 .apk 路径
  ├─ canRequestPackageInstalls? 否 → 引导「安装未知应用」设置
  ├─ 共享存储未授权? → 引导「所有文件访问」
  ├─ MediaScanner 扫描强制入库 → 后台按 DATA 查 MediaStore.Files
  │     ├─ 命中 → ACTION_VIEW(application/vnd.android.package-archive) 唤起系统安装器
  │     └─ 未命中/唤起失败 → PackageInstaller 会话流式直装兜底
  └─ 安装结果 → InstallReceiver 广播 Toast(成功/等待确认/失败原因)
```

### 5. 内置文件浏览器（`FileBrowserActivity`，可区分文件类型）

- **双数据源**（实测后端 `host.listDirectory` 只返回目录、不返回普通文件）：
  - 共享存储且已授权「所有文件访问」/ 本应用目录 → 本地 `File.listFiles` 直读：
    真实列出目录+文件+大小，目录在前按名排序，图标按类型区分（📁🖼️📦📝📄🗜️…）；
  - 其余路径（Termux 工作区等）→ 后端列表，**仅目录**，界面明示限制并提供
    「去开启权限 / 📂 Downloads 快捷跳转」。
- 顶栏：⌂主目录 / ↑上级 / ⟳刷新 / 📂Downloads / ⧉复制当前路径 / ✕退出；面包屑可点击跳转。
- 点击条目：本地模式按 isDirectory 直达/打开；远端模式沿用「后端探测目录→文件」语义。
- 入口：会话内容区第二行（Session log 正下方）「🗂 文件」按钮（v1.14.1 调整）；
  消息中路径点到的目录也会直达该浏览页。

### 基础设施（本端自研）

- `file/FileServeProvider.kt`：`content://com.aptuidsh.kui.files/<绝对路径>` 只读流，
  供系统应用打开共享存储文件（清单 grantUriPermissions）。
- `FileViewerActivity`：内置文本/图片查看（防 OOM 采样解码、2MB 截断、授权引导）。
- `PlayerActivity`：内置音/视频播放器。
- `InstallReceiver`：安装会话结果 Toast 回显。
- 清单新增：`REQUEST_INSTALL_PACKAGES`、3 个 Activity（浏览器/查看器/播放器）、Provider、Receiver。

## 滚动跟随稳定性修复（v1.14.2）

**问题**：流式输出每当渲染新一行时页面自动滚动，但会「一下滚到顶部、一下滚到底部」反复跳。

**根因（原有实现）**：
1. 消息列表无稳定 key（`items(size)` 按 index 渲染）；LiveFold 可能在中部/前部插入消息，
   导致滚动画面的目标索引在动画期间发生位移，滚落点漂移；
2. 用 `animateScrollToItem(messages.size)` 做长动画跟随，期间又有新事件重启协程，
   动画被中途取消/重跑，停在错误位置；
3. 滚动目标直接用 `messages.size`，但列表里还有顶部“加载更多”与流式底部“TurnStatus”两个
   额外条目，真实末项索引比 `messages.size` 更大，跟随时总差几行；
4. 跟随用动画 + 延时标志（isFollowing）在两个独立协程间传递，状态易撕裂。

**修复（`ui/ConversationView.kt`）**：
1. 每条消息给稳定 key（`role|turn|step|seq|time`），重拉/插入不再让滚动锚点漂移；
2. 底部判定改为只看“末项/锚点是否进入可视区”（`atBottomOf`），与像素阈值/动画解耦，
   用 `distinctUntilChanged` 只在状态变化时写回；
3. 自动跟随改为**瞬时精确钉底**：`scrollToItem(真实末项索引)`，索引 = 顶部加载更多 +
   消息数 + 流式 TurnStatus，杜绝长动画中途跳变；
4. 流式输出期间每 250ms 把视口重新钉到底部（文字逐字变高仍能看到最新行），
   用户一旦上滑阅读即自动停手（atBottom=false），回合结束自动退出循环。

## 交互细节调整（v1.14.3）

- APK 安装唤起提示(「正在唤起系统安装器…」/「安装请求已提交」)由系统 Toast 改为
  **应用内横条**:全宽、无圆角边框、灰色背景(自动适配亮/暗),2.6s 自动消失(新增 `AppBanner`)。
- 「回到底部」悬浮按钮:
  - 纵向位置从贴底改为**内容区底部向上 1/3 屏高处**;
  - 背景改为**透明**(去掉圆形底/边框/投影),仅保留带轻投影的 ↓ 图标,扩大触控区至 44dp。

## 提示条与流式滚动再优化（v1.14.4）

- APK 唤起提示横条(`AppBanner`):不再全屏通栏——改为**居中悬浮在内容视口中心**,
  宽度随文字自适应(WRAP_CONTENT),仍为灰色背景无圆角,2.6s 自动消失。
- 流式输出滚动平滑度:把跟随节奏从 250ms 一次"大跳"改为 **40ms 帧级连续钉底**——
  每轮先 `scrollToItem(真实末项)` 把视口贴到底,再休眠;文本增量每次只积累零点几行,
  滚动距离小且连续,消除"新行先渲染、整块内容随后被推上一行"的视觉跳变;
  用户上滑阅读时照旧自动停手,回合结束自动退出。

## 助手消息尾部工具组显示时机修复（v1.14.5）

**问题**:助手消息底部「时间+分支+复制」工具组(3 个按钮)总在流式输出期间、调用工具的间隙
就被渲染,而非回合真正结束后。

**根因(三处叠加)**:
1. `turnCompleted` 只在「turn/start 且此前未在运行」与「turn/end 且此前在运行」时翻转——
   插话/连续回合等场景 turn/start 时旧标记未清,流式中仍为 true;
2. `loadSessionHistory` 快照重拉把 `turnCompleted` 无条件置为 true,流式中途因掉帧/重连
   触发的重拉会误标「已结束」;
3. 渲染条件只检查 `turnCompleted && !streaming`,而 LiveFold 每步 assistant/message 落定即
   把该条消息 `streaming=false`(工具调用间隙正满足),导致工具组早显,且历史每一轮都重复渲染。

**修复**:
1. ChatActivity 事件翻转改为**幂等**:任何 `turn/start` → running=true、turnCompleted=false;
   任何 `turn/end` → running=false、turnCompleted=true;
2. 快照重拉仅当会话不在运行(`running=false`)时才把 turnCompleted 置 true;
3. ConversationView 工具组额外要求**该助手消息是列表最后一条**
   (`index == messages.size-1`),与「整轮结束 + 消息已落定」三条件齐备才显示,
   历史多轮不再重复出现工具组。

## 提问(question/requested)答题卡 + 审批卡同款 UI（v1.14.6）

### 官方协议(调研结论,移植语义而非代码)
- 助手端「提问工具」会让宿主发独立 mux 帧 `question/requested`:
  payload `{sessionId, questions:[{id, question, header?, detail?, options?:[{label,description?}], multiSelect?, intent?}]}`,
  **信封 rpcId 即后端等待队列的 key**;
- 用户作答后回 `POST /api/respond`:`client-response` 信封 + 复用该 rpcId,
  value `{sessionId, answer:{answers:[{id, selected:[label…], custom?}]}}`;
- 用户主动取消则以 `ok:false` + `{code:"cancelled"}` 应答结束等待。

### 本端实现
- **识别**:ChatActivity 监听 `question/requested`(与 `approval/requested` 平行),解析为
  `QuestionRequest(sessionId, muxRpcId, items[])` 存入会话状态(`pendingQuestion`);
- **答题卡 UI**(新 `ui/RequestCards.kt` 的 `QuestionCard`):
  一批问题逐题作答(标题区 + 选项单选○/●、多选☐/☑ + 自定义文本输入),最后一题统一提交;
  支持 plan-review 意图(用其 approve 文案作为单选项);
- **位置**:卡片渲染在内容区顶部、任务进度横条(TodoPanel)**正下方**,不随消息滚动;
- **样式**:无圆角边框、背景与侧边栏一致(SidebarBg),高度随选项数量自适应;
- **审批卡改造**:原「权限审批 Dialog」移除,`ApprovalRequest` 改渲染为
  同位置、同样式、同交互的 `ApprovalCard`(拒绝 / 允许一次);二者不会同时出现;
- **通信**:`DshClient.respondQuestion` 修正为复用 mux rpcId(原来漏传会 not-pending),
  新增 `respondCancel`(ok:false 取消);应答/取消成功即清除卡片。

## 工具组"整会话不显示"回归修复（v1.14.7）

**问题**:v1.14.5 修复"工具组流式中早显"后,出现新回归——回合结束后,整个会话的消息底部
工具组(时间+分支+复制)都不再显示。

**原因分析**:v1.14.5 把显示条件收紧为「必须是最后一条消息 + `turnCompleted == true`」,
而 `turnCompleted` 只会在两种时机置 true:(a) live 事件收到 `turn/end`;(b) 快照重拉且
`running == false`。实际运行中存在多条路径让该标记没有及时/正确地回到 true——
断线重连后历史快照重拉、重拉与 turn/end 的 200ms 去抖竞态、以及会话在流式中退出后再打开
(running 状态残留)等,都会使 `turnCompleted` 保持 false,于是满足条件的那条"最后一条消息"
也永远不渲染工具组 → 表现为整个会话都看不到。

**修复(ui/ConversationView.kt)**:结束判定从"必须 turnCompleted"放宽为
「`!running`(无进行中的回合,即 turn/end 已到)或 `turnCompleted` 任一成立即可」,
配合原有的「最后一条消息 + 已落定非流式」约束:
- 流式输出中/工具调用间隙:running=true 且 turnCompleted=false → 工具组保持隐藏(原修复目标仍成立);
- 回合结束(turn/end 使 running=false)或历史空闲会话 → 底部最后一条助手消息正常显示工具组;
- 即便个别时序下 turnCompleted 没被置回 true,只要 running=false 也能显示,不再整会话丢失。

## 工具组改为“每条助手消息都渲染”（v1.14.8）

**需求**:工具组(时间+分支+复制)不只出现在最后一条助手消息,而是**每条已结束的助手消息
底部**都要渲染(历史各轮亦如此)。

**判定规则(ui/ConversationView.kt)**:
- 一条助手消息自身已落定(非流式,仍在打字/思考中不显示);
- 且不会再有后续输出:
  - 中间消息(非列表最后一条):其后已有更新的内容(用户消息/新助手回复/中断错误横幅),
    必然已结束 → 总是渲染;
  - 最后一条消息:还需整轮空闲(!running 或 turnCompleted),防止工具调用间隙提前显示。
- 结束因素覆盖:正常 turn/end、模型余额不足/网络中断(表现为 turn/end error + 错误横幅)、
  用户点停止(session.cancel → turn/end)。这些最终都会把消息 seal(streaming=false),
  因此每条结束的助手消息底部都会出现工具组;错误横幅本身不渲染工具组。

## 滚动跟随引擎 v3:小距连续追底(先推后渲染,v1.14.9)

**问题(重申)**:内容流式变高时,若一次 scrollToItem 瞬移到最底,视觉上就是
「新行先整行出现在底部空隙,随后整块内容瞬间上移一行」。

**新机制(ui/ConversationView.kt)**:
1. **不再周期性整体瞬移**:改为持续(每 ~8–24ms)检查"底部 2dp 锚点是否已被顶出视口";
2. 一旦发现最新内容超出视口底部(有新行要显示),就执行一次**小距离 animateScrollToItem**:
   因为每次检查间隔极短,动画只需移动"刚增长的那一点点",多行增量会被拆成连续多次
   小动画 —— 视觉 = 旧内容持续平滑上推、新行从底部自然进入,不再"整块跳一行";
3. 用户上滑阅读(atBottom=false)即完全停手,回合结束自动进入低频检查;
4. 会话进入/从极顶部开始时一次性定位到最新内容;
5. 缩小底部"死区"(contentPadding 12→6dp、锚点 4→2dp),避免新行先填充死区被看见再被推。

## 滚动跟随补充“手动上滑闸门”(v1.14.10)

**问题**:v1.14.9 的追底引擎在用户上滑查看历史时仍会把它抢回底部,导致无法手动上滑。

**根因**:引擎只要读到 `atBottom==true && 底部锚点不可见` 就立刻 animateScrollToItem,
而上滑动作本身第一步就会让锚点不可见;`atBottom` 标志的更新又晚于引擎 8ms 的检查,
于是每次上滑刚起手就被拉回底部。

**修复(ui/ConversationView.kt)**:在引擎滚动前置一道**用户手势闸门**——
任何时刻 `listState.isScrollInProgress == true`(用户触摸、惯性滚动,或跟随动画自身)都
**绝不发起滚动**;只有完全静止且确认仍在底部、且最新内容已超出视口时才做一次小距离动画。
效果:用户上滑/惯性翻历史期间引擎全程停手,`atBottom` 由滚动监听自然翻转为 false;
用户停在历史中部后引擎不再干预,仅当停在底部附近(<100px)时允许小幅吸底。

## 跟随闸门控制器(中间程序)重构(v1.14.11)

**需求**:不再用 `atBottom/isScrollInProgress` 猜,而是用一个全程生效的「中间程序」专职管理
自动滚动开关:
- 识别到**用户上滑**(视口朝列表开头/更早内容方向移动,index 变小或同 index offset 变小)
  → **立刻关闭自动滚动**;
- 任何时刻**底部锚点回到屏幕视口内** → **立刻恢复自动滚动**(即使没有流式输出也全程生效)。

**实现(ui/ConversationView.kt)**:
- 新增 `autoFollow` 状态 + 跟随闸门控制器协程:持续采样 `ScrollSig(首可见 index/offset、
  是否滚动中、底部是否可见)`;滚动方向累计朝早即关,底部可见即开(优先级最高);
- 跟随引擎仅当 `autoFollow==true` 时运行;Effect 以 `autoFollow` 为 key,
  关闭瞬间取消引擎残留动画,重新开启时自动重启并先定位到最新内容;
- 内容增长顶出锚点、跟随动画(朝底部方向)都不会被误判成用户上滑。

## 上滑仍被拉回:改用强信号判定 + 引擎 busy 隔离(v1.14.12)

**现象**:非流式时上滑屏幕,手一松立刻跳回底部。

**根因(两处)**:
1. v1.14.11 的方向判定("首可见 index/offset 变小")在快速上滑时可能只出现 1 帧,未及触发
   关闭;而引擎空闲循环(120ms)看到"底部锚点不可见且 autoFollow 仍为 true"就执行
   animate 回底 → 手一松即被拉回;
2. 引擎自身追底动画也会令 isScrollInProgress=true,需要与"用户滚动"隔离。

**修复(ui/ConversationView.kt)**:
- 控制器改用**无歧义强信号**:从"底部可见"开始,只要「正在滚动 && 底部锚点滑出视口 &&
  非引擎动画」→ 立即 autoFollow=false(无论滑多快、无论是否流式);
  底部重新出现在视口 → 立即 autoFollow=true(规则②,优先级最高);
- 新增 engineBusy 标记:引擎执行 scrollToItem/animateScrollToItem 全程置位,控制器据此
  绝不把引擎动画误判成用户手势;
- 引擎以 autoFollow 为 key:关闭瞬间整个引擎协程(含进行中的动画)被取消,不再有"残留
  滚动把用户拽回底部"的可能。

## 滚动跟随诊断版(v1.14.13)

- 行为与 v1.14.12 相同(强信号闸门 + engineBusy 隔离)。
- 新增诊断打点:autoFollow 开关翻转、引擎 quick-position / follow-animate 触发时,
  追加写入 `Downloads/aptuidsh_scroll_debug.log`(同时进 AppLog)。
- 用途:若真机上滑仍偶发"被拉回",复现一次后把该日志文件发回即可定位
  (应看到:上滑期间 autoFollow true→false 的翻转是否发生在拉回之前)。

## 会话流 C 档重构：日志/窗口/折叠统一(v1.15.0)

按官方 dsh client-runtime 的「事件日志 + 窗口 + surface 折叠」三层重构会话流,移除
"每轮结束自动重拉日志"的结构性偏差,改为「打开/重连一次快照 + 纯增量折叠 + 罕见 gap 校准」。

### 数据层(新增 `ui/SessionLog.kt`,删除 `ui/LiveFold.kt`)
- 单一折叠器 `SessionLog`:**历史回放**(`fromHistory`)、**实时增量**(`append`)、
  **更早分页**(`prepend`)、**gap 修复**共用同一折叠核心;
- 历史与实时产出同一套字段(turn/step/time/lastSeq/turnStartTime/firstTokenTime/
  completedTime/error/附件),消除"每次重拉整表 key 漂移";
- 保留原始事件窗口,`prepend`/gap 修复可整窗重折叠;孤立用户消息裁剪仅用于历史/校准回放。

### 事件循环(`ChatActivity`)
- 打开/切换/fork = doOpen(历史快照一次),之后全部走 `SessionLog.append` 增量;
- 在途事件(loading/repairing)进 liveBuffers,完成后按序重放;
- `seq<=基线` 丢弃、`seq 缺口` 仅触发一次 `repairSessionGap`(拉一页 tail 重建窗口),不再整表重拉;
- **移除** turn/end 后 200ms 全量重拉 与 旧 gap 150ms 全量重拉;
- 统计/上下文/权限继续由 `session/projection` 帧驱动;turn/start|end 幂等翻转 running/turnCompleted;
- `loadMoreHistory` 走 `SessionLog.prepend`(前插更早事件后整窗重折叠)。

### 一致性与回归
- 错误横幅、工具组尾栏、todo、附件、请求卡片、滚动全部沿用会话快照派生结果;
- 消息 key 语义(role|turn|step|lastSeq|time)在历史与实时间保持一致,不再随重拉漂移。
