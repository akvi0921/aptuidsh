# 消费统计功能 + 底部统计真实数据修复(1.14.22)

## 一、底部统计概览显示真实数据

### 根因(两处真实缺陷)

**缺陷 1:`buildStatsText` 收了 `tokenUsage` 却完全没用**

`BackendParser.kt` 的函数签名是 `buildStatsText(stats, tokenUsage)`,但函数体只读
`sessionStats.decodeTokens`(仅输出 token),`tokenUsage` 参数从未被引用。
后果:真正占计费大头的**输入侧**——缓存命中(便宜)与未命中(贵)——全部不可见。

实测一轮对话的对比:

| 口径 | 旧实现显示 | 真实值 |
|---|---|---|
| 输出 | 79 tok | 79 tok |
| 输入(总) | **完全没显示** | 8004 tok(命中 7552 + 未命中 452) |

即旧实现漏掉了 **99% 的 token 量**:这一轮 8004 输入里 7552 是缓存命中(94%),
而缓存命中与未命中的单价差 **50 倍**(0.02 vs 1 元/百万),不显示分项根本无法判断成本。

**缺陷 2:实时更新时把 tokenUsage 传成 null**

`ChatActivity.kt` 的 `sessionStats` 投影分支写死 `buildStatsText(obj, null)`。
`sessionStats` 与 `tokenUsage` 是**两条独立的投影帧**,各自到达;
在 `sessionStats` 帧上强制传 null,意味着每次实时更新都会丢掉 token 分项,
只有重拉整段历史(loadSessionHistory)时才对。

### 修复

- 新增 `TokenUsage` / `SessionStats` 两个数据类,逐字段保留原始投影数值
  (不做字符串化,避免二次解析丢精度、丢分项)。
- `buildStatsText` 按官方计费口径展示:总输入 / 命中缓存 / 未命中缓存 / 总输出。
- `sessionStats` 帧不再传 null;新增 `tokenUsage` 投影帧分支独立消费。
- **实测线上形状注意点**:输入侧字段名是 `uncachedInputTokens`,不是 `inputTokens`
  (`{uncachedInputTokens, outputTokens, cacheReadTokens, cacheWriteTokens}`)。
  写错字段名会静默拿到 0——已单测覆盖该回归。

## 二、消费统计弹窗(侧边栏新增按钮)

### 按钮位置

- 图标:`49.png`(¥ 符号)→ `res/drawable-nodpi/ic_side_usage.png`(61×61,原图导入)
- 收纳态:位于**素材/模型图标(icon 10)正下方**一行(`topPad + touch * 5`)
- 展开态:紧随 icon 10 下方一行(`expIcon10Y + 24.dp + 4.dp`),同列对齐

### 弹窗结构(按需求逐行)

| 行 | 内容 |
|---|---|
| 标题 | 消费统计 |
| 1 | 提供方 \| 余额(右侧,真实拉取)+ 刷新 |
| 2 | 分割线 |
| 3 | 总输入 token / 输入命中缓存 / 输入未命中缓存 / 总输出 token |
| 4 | 轮次 / 步数 / LLM 耗时 / 工具耗时 |
| 5 | 输出速度(tok/s)/ 系统提示词消耗 |

余额行下方附带充值/赠送明细;底部标注计算口径。

### 余额实现(`net/BalanceClient.java`)

官方接口 `GET https://api.deepseek.com/user/balance`(实测可用,返回
`{is_available, balance_infos:[{currency,total_balance,granted_balance,topped_up_balance}]}`)。

**关键设计——按提供方判定,而非按模型判定**:余额挂在账户(API Key)上,
同一提供方下所有模型共享同一份余额。因此:

- `deepseek-official` → 真实查询并显示余额;
- 其他提供方(如 `qwen-token-plan`/MiniMax)→ 直接显示「当前提供方不支持获取余额。」,
  **不会**错误地把 DeepSeek 的余额显示给别的提供方。

密钥来源:DSH 凭据文件 `~/.dsh/.credentials.yaml` 的 `refs.DEEPSEEK_API_KEY`。
解析器只在 `refs:` 段内查找(避免把 `records:` 段的同名键误当密钥),
支持引号包裹,缺失时返回明确文案而非抛异常。

### 两个派生指标的口径(经确认)

- **输出速度** = 总输出 token ÷ LLM 耗时(`llmMs`)。
- **系统提示词** ≈ 首轮未命中缓存的输入 token(首轮输入几乎全部是系统提示词 + 首条消息),
  首次取到后**锁定不再被后续轮次覆盖**,否则会随对话增长而失真。

## 三、验证

1. **余额链路**:真实凭据文件 + 真实响应解析,14/14 用例通过
   (含"忽略 records 段同名键""refs 段结束后停止""去引号""空 key 安全"等边界)。
   真实余额实测 ¥6.92。
2. **token 解析**:9/9 用例通过,含"不误用 inputTokens 字段"回归用例。
3. **端到端**(真实跑一轮对话,投影逐字段核对):

   ```
   tokenUsage   : {uncachedInputTokens:452, outputTokens:79, cacheReadTokens:7552, cacheWriteTokens:0}
   sessionStats : {turns:1, steps:1, llmMs:1609, toolMs:0, ttftMs:1119, decodeMs:490, decodeTokens:79}
   → 总输入 8004 / 命中 7552 / 未命中 452 / 输出 79
   → 轮次 1．步数 1 / LLM 1609ms．工具 0ms
   → 输出速度 49.1 tok/s / 系统提示词 452 tok
   ```
4. 构建:1.14.22(versionCode 103)编译通过。

## 四、影响面

- 新增:`net/BalanceClient.java`、`ui/ConsumptionStatsDialog.kt`、`ic_side_usage.png`
- 修改:`ui/BackendParser.kt`、`ui/MessageModels.kt`、`ui/Sidebar.kt`、`ChatActivity.kt`、`app/build.gradle`
- 不改协议、不加依赖、不触碰会话流折叠与审批链路。

## 五、已知边界

- 余额仅在**本机存在 `~/.dsh/.credentials.yaml`** 时可用;换设备需重新配置。
- 系统提示词为**近似值**(首轮未命中缓存输入),并非后端单独提供的字段——
  DSH 不产出"系统提示词 token"这一独立指标。
- 非 DeepSeek 提供方一律显示"不支持",即使该提供方未来提供了余额接口,
  也需在 `BalanceClient.BALANCE_CAPABLE_PROVIDERS` 中显式登记才会启用。
