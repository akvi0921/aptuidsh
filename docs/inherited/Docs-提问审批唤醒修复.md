# aptuidsh 提问/审批卡片唤醒问题 — 根因、修复与新增方法(最终版 1.14.19)

日期:2026-09-10 · 版本:1.14.19(versionCode 100) · 已移除全部诊断日志

---

## 一、问题现象与目标

- 实时场景:会话中助手发起提问(`ask_user_question`)/权限审批 → 弹窗应立刻出现;
- 重启/返回重进场景:不处理直接离开 → 再进入该会话 → 弹窗应恢复;
- 二次提问场景:处理掉弹窗后,同一会话或新会话再次提问 → 弹窗应再次出现。

(未修复前只有"实时"一条能用,其余两条都失效,且相互牵连出现过多轮回归。)

## 二、问题根因(共三层)

### 根因 1:客户端错过了服务端的"重放"帧
- 后端(`dsh-host-apiproxy`)把未处理的提问/审批存在进程内存
  `pendingQuestions`/`pendingApprovals` 中,key = mux 帧信封的 `rpcId`;
- **每次新建 `/api/events.mux` 订阅都会重放仍待处理的 `question/requested`、
  `approval/requested` 帧**(rpcId 不变、可正常应答);
- APP 的事件通道在 `MainActivity` 就启动,而监听器要进入 `ChatActivity` 才挂载,
  重放帧到达时无监听器被直接丢弃 → 重启后卡片丢失。

### 根因 2:进程存活时"返回首页再进会话"不会重放
- "关掉 APP"多为按返回键切回开屏首页,进程仍存活、mux 连接不断、也不会再重放;
- `ChatActivity` 被销毁后其 `sessions`(含 pending 卡片)内存态丢失;
- 重新进入时既无新重放、缓存也被清空 → 卡片永久丢失。

### 根因 3:跨线程"读-改-写"竞态导致二次提问弹窗被覆盖(最关键)
- `onMuxEvent` 在 EventStream **工作线程**直接改 `sessions`(Compose 状态);
- 消息折叠(`liveEvents` 消费器)在**主线程**也改同一个 `sessions`;
- 两者都是 `sessions = sessions.toMutableMap().apply{...}`(读旧值→改副本→写回),
  并发时主线程用旧快照覆盖了刚写入的 `pendingQuestion` → 二次提问弹窗不显示。

## 三、相对"未修复之前"新增的修复方法清单

### 1. `EventStream.java`(协议层新增)
| 方法/字段 | 作用 |
|---|---|
| `pendingInteractionFrames`(LinkedHashMap) | 进程级"当前待处理"视图:requested 记录、resolved 删除 |
| `updatePendingInteractionView(...)` | 每个交互帧都更新该视图(不管有无监听器) |
| `snapshotPendingInteractions()` | 供 UI 进入会话时拉取当前待处理请求帧 |
| `setChannelConnected(...)` 内 mux 断开清空视图 | 重连后由重放帧重建,避免断线期间被解决的请求残留成陈旧卡片 |

### 2. `ChatActivity.kt`(UI 层新增)
| 方法/机制 | 作用 |
|---|---|
| `applyStateEnvelope(env)` | 统一在主线程应用 approval/question 的 requested/resolved 与 session/queue,一处处理 |
| `stateFrames`(Channel) | 交互/排队帧统一入队,不再在工作线程直接改 `sessions` |
| `LaunchedEffect` 消费 `stateFrames` | 与 `liveEvents` 折叠器、投影消费器同在主线程顺序执行,消除竞态 |
| `applyPendingInteractionView()` | 进入会话时从 `snapshotPendingInteractions()` 拉取并恢复卡片 |
| `question/resolved` 帧处理 | 他端作答/取消/超时后本端同步清除提问弹窗 |
| `session.list` 重建时合并保留 `pending*` | 防止会话表整表重建时把待处理卡片状态冲掉 |

### 3. 关键设计原则
- 「捕获」与「展示」解耦:EventStream 负责持续维护 pending 视图(进程级、与 UI 生命周期无关),
  ChatActivity 负责进入会话时拉取展示;
- 所有会改 `sessions` 状态的帧统一走主线程串行,消除跨线程竞态;
- 不重启事件通道(避免 1.14.14 那种双线程/双 socket 互抢导致实时弹窗失效的回归)。

## 四、最终覆盖场景

| 场景 | 结果 |
|---|---|
| 会话中实时提问/审批 | 立刻弹窗 |
| 杀进程重启 / 返回首页再进会话 | 进入会话拉取视图 → 弹窗恢复 |
| 处理弹窗后二次提问(同会话/新会话) | 主线程串行,不被消息折叠覆盖 → 立刻弹窗 |
| 他端作答/取消 | resolved 帧 → 视图删除 + UI 同步清除 |

## 五、发布说明

- 已移除全部诊断日志(`DebugLog.kt` 及所有 `DebugLog.log(...)` 打点),
  APP 不再产生任何日志文件;
- `gradle assembleDebug --no-daemon -x lint` → BUILD SUCCESSFUL;
- dex 校验:无 `DebugLog` 类、无日志路径字符串,修复方法(`applyStateEnvelope`、
  `snapshotPendingInteractions`)均已编译进包。
