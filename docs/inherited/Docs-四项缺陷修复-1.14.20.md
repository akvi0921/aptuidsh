# aptuidsh 四项缺陷修复说明(1.14.20)

日期:2026-09-10 · 版本:1.14.20(versionCode 101)

## 修复内容

### Bug 1 · 发送后文字/附件草稿残留
**根因**:发送成功的回调在 DshClient 后台线程执行,直接对共享 `sessions` 状态做
"读旧值→改副本→写回";同一时刻主线程的消息折叠/队列帧消费也在写 `sessions`,
后台写入被旧快照覆盖,清空草稿的整块写入丢失。

**修复**:`ChatActivity.kt`
- `suspendRpc` 恢复续体统一改到主线程(`scope.launch { cont.resume(...) }`),
  使所有 RPC await 之后的 `sessions` 写入都在主线程串行;
- `sessionPrompt`/`respondApproval`/`respondQuestion`/`respondCancel`/
  `agentPresetSelect` 等直接回调里的 `sessions` 写入全部用 `scope.launch` 切回主线程。

### Bug 2 · 底部输入卡片发的消息出现在插话排队列表
**根因**:与 Bug 1 同一竞态——`session/queue` 的"清空帧"写入被后台回调的旧快照还原,
导致自己刚发的消息残留在排队列表。

**修复**:同 Bug 1(所有 `sessions` 写入统一主线程串行后,竞态消除)。

### Bug 3 · 推理折叠行流式中上下多出空白
**根因**:`SessionLog` 每一步新建一条独立流式消息,每条助手消息带 16dp 底距;
推理行独占一条消息时,上方(上一条消息底距)与下方(本条底距)各出现约一行空白;
该步结束后消息合并、空白才消失。

**修复**:`ConversationView.kt`
- `AssistantMessage` 增加 `compact` 参数:流式中的消息,或后面跟着同一轮(turn 相同)
  流式续接消息的消息,底距改为 0;其余保持 16dp。

### Bug 4 · 展开的折叠行在新输出到来时自动收起
**根因**:LazyColumn item key 含 `lastSeq`,而 `lastSeq` 每个流式增量都变 →
key 变化 → 整个 item 被销毁重建 → `remember { mutableStateOf(false) }` 展开状态丢失。

**修复**:`ConversationView.kt` + `SessionLog.kt`
- `messageKey` 去掉 `lastSeq` 与 `step`,改为 `role|turn|time`;
- 合并/回填时不再更新 `time`(落定时间由 `completedTime` 承载),保证 key 全程稳定。

### Bug 5 · 图片+文件同发附件丢失 —— 暂缓(用户要求)

## 验证

- `gradle assembleDebug --no-daemon -x lint` → BUILD SUCCESSFUL;
- 真机建议验证:
  1. 连发多条消息,观察输入框草稿是否都及时清空、附件芯片是否一起消失;
  2. 空闲时发消息后立即看插话排队列表,不应出现自己发的消息;
  3. 让助手走推理(第 1 次与第 2 次均可),观察推理行是否全程紧贴上下行;
  4. 流式输出中展开工具/推理折叠行,应保持展开不再自动收起。
