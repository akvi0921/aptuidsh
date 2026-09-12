# 消费统计 UI 重做 + 余额取不到的真正根因修复(1.14.23)

## 一、余额报「未找到密钥」——根因是 Android 沙箱,不是代码 bug

### 实测证据

```
Termux UID : 10199
APP    UID : 10328      ← 不同的应用沙箱

drwx------  /data/data/com.termux/files/home          (0700, 属 Termux UID)
drwx------  /data/data/com.termux/files/home/.dsh      (0700)
-rw-------  /data/data/com.termux/files/home/.dsh/.credentials.yaml  (0600)
```

上一版 `BalanceClient` 直接读 `~/.dsh/.credentials.yaml`。该文件权限 0600、
其**上级目录 0700 且属 Termux UID**,APP 连目录都无法进入(`chdir` 就被拒),
无论代码怎么写都读不到——这是操作系统级隔离,不是实现缺陷。

这也解释了为什么报错文案是「未找到密钥」而不是权限错误:APP 侧 `canRead()`
直接返回 false,走了"文件不存在"分支。

### 正确路径(已实现)

DSH 后端自身有 `credentials.*` RPC 面(HTTP,不经过文件系统),实测可用:

```
credentials.describe({refs:["DEEPSEEK_API_KEY"]})
  → {"credentials":{"DEEPSEEK_API_KEY":{"configured":true,"source":"file","writable":true}}}

credentials.set({ref:"DEEPSEEK_API_KEY", value:"sk-..."})  → {"ok":true}
```

因此密钥链路改为(与需求一致:每次打开检查,都找不到则主动弹框):

1. **每次唤起弹窗先问后端** `credentials.describe` → 得到 `configured` 布尔;
2. 用 **APP 本地保存的 key**(SharedPreferences,仅本应用可读)查余额;
3. 本地无 key 且后端也未配置 → **主动弹出密钥输入框**。

> 说明:后端 RPC 只暴露"是否已配置",**不返回密钥明文**(这是有意的安全边界)。
> 所以即然后端已配置,APP 也仍需用户在本机补输一次才能自己查余额——
> 此时提示文案明确写「后端已配置密钥,但 APP 无法读取;请在此输入一次以便本机查询」,
> 而不是含糊地报"未找到密钥"。

### 密钥输入框(点击弹窗中的「提供方」名称唤起)

- 密码框(可切换显示/隐藏),占位 `sk-...`;
- 勾选项「同时写入 DSH 后端(推荐)」:
  - 勾选 → `credentials.set` 写入后端,后端也能用该 key(密钥不出本机);
  - 不勾 → 仅存 APP 本地,后端不受影响;
- 已存 key 时提供「清除」;
- 弹窗内直接说明「APP 与 DSH 运行在不同系统沙箱,无法读取 DSH 凭据文件」,
  避免用户误以为 APP 有 bug。

## 二、弹窗 UI 按草图重做

### 布局对照

```
┌────────────────────────────────┐
│ 消费统计                        │
│ 提供方  deepseek      余额: 0.00│  ← 提供方可点击 → 密钥输入框
├────────────────────────────────┤
│  输入  │  命中  │ 未命中 │ 总输出│  ← 四等分列,列间竖线
├────────────────────────────────┤
│  轮次  │  步数  │ LLM/s  │ 工具/s│
├────────────────────────────────┤
│    输出tok/s     │  系统提示词tok │  ← 两等分列
└────────────────────────────────┘
```

### 具体改动

| 项 | 旧版 | 新版(按草图) |
|---|---|---|
| 数据行 | 左标签右数值的列表 | **等分单元格网格**,单元格间竖线分隔 |
| 第三行 | 总输入/命中/未命中/总输出(4 行列表) | **四列并排**:输入 / 命中 / 未命中 / 总输出 |
| 第四行 | 轮次/步数/LLM耗时/工具耗时(4 行列表) | **四列并排**:轮次 / 步数 / LLM/s / 工具/s |
| 第五行 | 上下两行 | **左右两列**:输出tok/s \| 系统提示词tok |
| 提供方 | 纯文本 | **红色可点击**,点击弹密钥输入框 |
| 余额 | `¥6.92` + 刷新按钮 | **纯数字** `6.38`,**无刷新按钮** |
| 刷新 | 手动按钮 | **每次唤起弹窗自动拉一次真实数据** |
| 耗时单位 | `1.6s` / `0m0s` | 统一为秒(`LLM/s`、`工具/s` 列名对应) |

余额显示改为纯数字:币种由左侧「余额:」标签隐含,与草图一致且多语言下更干净。
充值/赠送明细、口径说明等次要信息已按草图精简移除。

## 三、验证

1. **密钥来源决策逻辑 12/12 用例通过**,覆盖五种组合(后端已配置/本地有 key ×
   是否支持余额),含关键回归:**「后端已配置+本地无 key」必须走引导输入而非直接失败**。
2. **凭据状态解析**:`configured:true/false`、缺 `credentials` 段、null 均安全。
3. **真实链路复测**:`/user/balance` 返回 `total_balance: 6.38`(纯数字显示用),
   `credentials.set` 写入后 `describe` 仍报告 `configured:true`。
4. 构建:1.14.23(versionCode 104)编译通过。
5. 已确认测试未破坏凭据文件(`records`/`refs` 两段完整)。

## 四、影响面

- 新增:`ui/ApiKeyDialog.kt`
- 重写:`ui/ConsumptionStatsDialog.kt`(改为网格布局)、`net/BalanceClient.java`(改为 RPC 取凭据 + 本地兜底)
- 修改:`ChatActivity.kt`(两步密钥链路 + 弹窗状态)、`app/build.gradle`

## 五、已知边界

- **APP 本地保存的 key 是 SharedPreferences 明文**(位于 APP 私有目录,
  仅本应用可读;未 root 的设备其他应用无法访问)。如需更高强度,
  后续可接入 `EncryptedSharedPreferences`。
- 后端 RPC 不返回密钥明文,因此**后端已配置 ≠ APP 能自动查到余额**,
  仍需在本机输入一次;这是刻意的安全设计,已在 UI 文案中说明。
- 非 DeepSeek 提供方一律显示"不支持",需在
  `BalanceClient.BALANCE_CAPABLE_PROVIDERS` 显式登记才会启用。
