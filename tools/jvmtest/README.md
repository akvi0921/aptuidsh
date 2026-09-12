# 适配层 JVM 实机测试台

直接编译并运行 APP 里**真实的** `net/ApiCompat.java` 与 `net/MuxClient.java`
（只对 `android.util.Base64` 与 `DshAuth` 打桩），对着 `127.0.0.1:3081` 上真实的
dsh 0.1.5 后端跑一遍。

```bash
bash tools/jvmtest/run.sh [cookie文件路径]
```

## 它验证什么

- **A 组**：`ApiCompat.mapMethod` + `ApiCompat.buildArgs` 为每个方法生成的请求体，
  逐个真实 POST 到 `/api/<method>`，检查服务端是否接受（`ok:true`）。
- **B 组**：`MuxClient` 的真实 WebSocket 握手与帧解析——
  连 `/api/remote.mux`、`open $events` 取 ready 帧、`open session/follow` 取 opening snapshot。

已知的「非缺陷」失败会被标注为 N/A：
- `session.search`：该部署把会话查询索引配成 `openAt: "never"`，搜索被显式禁用。
- `llm.discoverModels`：`agent-default-model` 命名空间未注册模型发现能力。

## 它已经抓出过的真实缺陷

| 缺陷 | 后果 | 
|---|---|
| `new URL("ws://…")` | `java.net.URL` 没有 ws 协议处理器 → **会话历史必然崩溃** |
| `goals/get` 用 `sessionId` | 描述符形参名是 `agentId` → 参数校验失败 |
| `subagents/list` 包了 `request` | 描述符要的是扁平 `parentSessionId` |
| `commands/list` 又包了一层 `args` | 描述符要的是扁平 `agentId` |
| `llm/discoverModels` 只传 `settingsNs` | 描述符还要 `request` |

> 升级 dsh 版本后请先跑这个测试台：方法名与形参名的漂移会第一时间暴露。
