# 消费统计弹窗数据为空修复(1.14.24)

## 现象

1.14.23 的弹窗中**余额正确**(6.34),但内部所有统计格全是空的(输入/命中/未命中/总输出、
轮次/步数/LLM·s/工具·s、输出tok/s、系统提示词tok)。

同时**底部统计行却有真实数据**:`4轮．230步|LLM 36m15s．工具57m36s|入 24.2M(缓存 24.0M．未命中 120)．出 83.1k tok`。

"底部有、弹窗没有"这个割裂现象本身就是线索。

## 根因

弹窗读的是会话状态里**缓存的结构化字段** `tokenUsage / sessionStats`,而这两个字段只在两处写入:

| # | 写入时机 | 问题 |
|---|---|---|
| 1 | `loadSessionHistory` | **打开会话时仅执行一次** |
| 2 | 实时投影帧 `sessionStats` / `tokenUsage` 到达 | 只在有活动时到达 |

而底部统计行读的是 `statsText`——一个在历史快照里**就已经算好并存的字符串**,所以它一直有值。

于是:会话打开时(第一处)用量还是 0,之后若没有新的投影帧到达(第二处),
结构化字段就**永远停在初始值 0**,而字符串 `statsText` 却在打开那一刻被算好并留存。
这正好解释了截图里"底部有、弹窗空"。

实测复现(新建会话,分别取两个时点):

```
[打开会话时]      tokenUsage = {uncachedInputTokens:0, outputTokens:0, cacheReadTokens:0}
[弹窗重新打开时]  tokenUsage = {uncachedInputTokens:452, outputTokens:133, cacheReadTokens:7552}
```

第一行就是弹窗此前读到的值——全 0。

## 修复

按需求「每次打开弹窗,内部所有数据都进行一次真实的读取」,新增
`ChatActivity.refreshUsageSnapshot(id)`:

- 每次打开弹窗**现场调用** `session.history`(maxMessages=1,只为取 projections,不拉消息体);
- 用返回的 `tokenUsage / sessionStats / contextPressure / permissions`
  **覆写**会话状态,使弹窗与底部统计行同源同刻;
- 与余额拉取并列在同一个 `LaunchedEffect(usageDialogVisible, currentId)` 中触发。

另:拉取期间各格显示 `…` 占位,避免把"尚未拉到"误显示成真实的 0。

## 验证

同一会话两个时点对比(真实后端):

```
[打开会话时]      tokenUsage 全 0            ← 缺陷复现
[重新打开弹窗时]  tokenUsage  = {未命中:452, 输出:133, 命中:7552}
                 sessionStats = {turns:1, steps:1, llmMs:1205, toolMs:0}

=== 弹窗实际渲染值 ===
  输入   : 8004
  命中   : 7552
  未命中 : 452
  总输出 : 133
  轮次   : 1     步数: 1
  LLM/s  : 1.2   工具/s: 0.0
  输出tok/s: 110.4
  系统提示词tok: 452
```

构建:1.14.24(versionCode 105)通过。

## 影响面

- 修改:`ChatActivity.kt`(新增 `refreshUsageSnapshot` + `usageLoading` 状态)、
  `ui/ConsumptionStatsDialog.kt`(loading 占位)、`app/build.gradle`
- 不改协议、不加依赖、不触碰余额链路(余额本就正常)。

## 说明

本次只解决"打开弹窗时不重新读"。实时增量投影帧的消费逻辑(1.14.22 引入)
保持不变——它负责会话进行中的跟随更新;两者是互补关系:
**弹窗打开 = 强制对齐一次真实值**,实时帧 = 会话活跃期间持续跟随。
