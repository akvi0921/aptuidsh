# dsh 0.1.5 协议勘误与适配说明

> 本文记录 2026-09-12 在真机（Termux + proroot Ubuntu 24.04 + dsh 0.1.5-rc.1）上
> **实测**得到的协议差异。所有结论都有对应的 curl / node 复现命令，不是推测。
> 原生前端（继承自 dsh-aui，原本面向 dsh 0.1.1-rc.2）与新版本之间的差异由
> `net/ApiCompat.java` + `env/DshAuth.java` + `net/EventStream.java` 三处收敛。

---

## 一、鉴权（0.1.1 无 → 0.1.5 强制）

### 现象
```
$ curl -X POST http://127.0.0.1:3081/api/host.describe -d '{}'
HTTP/1.1 401 Unauthorized
unauthorized
```
`GET /` 返回：
```
dsh web authentication required; reopen the URL printed by dsh web.
```

### 机制（读自 `@deepseek-ai/dsh-client-connection/lib/index.js`）
```js
requestRejection(request) {
  if (!isTrustedApiRequest(request, this.trustedHosts)) return 403;
  return this.browserAuth.isAuthenticated(request) ? undefined : 401;
}
```
- `isTrustedApiRequest`：Host 必须是回环地址（或 `--trusted-host` 声明的），
  且 `Sec-Fetch-Site` 不能是 `cross-site`、`Origin` 必须与 Host 同源。
  **本机无 Origin 的请求是"可信"的，但仍要过第二关。**
- `isAuthenticated`：只认 **签名 Cookie**，没有别的旁路（没有 Bearer、没有开关）。

### Cookie 怎么来
1. `dsh web` 启动时把一次性 launchToken 打到 stdout：
   `dsh web: http://127.0.0.1:3081/?token=3wnFCj...`
2. 对该 URL 发 `GET`（**不要跟随重定向**，要读 `Set-Cookie`）：
   ```
   HTTP/1.1 303 See Other
   set-cookie: dsh-auth-w3iJaA6qw3qDSBs2Itl-h4S-Y-ZeYCC-N_iZO-eI_qw=v1.eyJ2ZXJzaW9uIjoxLCJhdXRob3JpdHkiOiIxMjcuMC4wLjE6MzA4MSIsImlzc3VlZEF0IjoxNzg5MjEzMDk0NjA1LCJleHBpcmVzQXQiOjE3OTkyMTMwOTQ2MDV9.iyyZ...
   ```
   Cookie 名 = `dsh-auth-` + base64url(sha256(authority))，
   authority 就是 `127.0.0.1:3081`；载荷是签名过的 `{version,authority,issuedAt,expiresAt}`。
3. 之后所有 `/api/*` 请求带 `Cookie: dsh-auth-xxx=v1.yyy.zzz` 即可。

### 关键性质（决定了 APP 的实现方式）
- **Cookie 的签名密钥持久化在 `.credentials.yaml`**，所以 Cookie 在 dsh 重启后依然有效；
  但 **launchToken 每次进程启动都会变**。
- 默认有效期 `cookieMaxAgeDays = 30`。
- APP 侧实现：`DshAuth` 从 stdout 抓 token、交换 Cookie、持久化到 SharedPreferences，
  收到 401 时自动 `invalidate + exchange` 并重试一次。

---

## 二、RPC 载荷信封（平铺 → args）

0.1.5 的 typert 网关要求 `payload` 里**有且只有一个** `args` 普通对象：

```json
{"type":"client-request","rpcId":"<uuid>","method":"session/list","payload":{"args":{...}}}
```

缺失或多余键的错误信息非常明确：
```
{"ok":false,"error":{"code":"gateway/internal",
 "message":"Remote payload must contain exactly one plain-object args field"}}
```

而 `args` 的**键名必须与 typert 描述符的形参名一致**，可用下式从 dsh 自带的分发器里直接读出来：

```bash
grep -o 'case "[a-zA-Z]*/[a-zA-Z]*": return [^;]*;' \
  node_modules/@deepseek-ai/dsh-client-connection/lib/client.js
```

实测对照表（左：旧平铺载荷，右：新 args 键名）：

| 方法 | args 键 | 例 |
|---|---|---|
| `session/list` | `_request` | `{"args":{"_request":{}}}` |
| `session/create` | `request` | `{"args":{"request":{"cwd":"/root/workspace"}}}` |
| `session/prompt` | `request` | `{"args":{"request":{"requestId":"<uuid>","sessionId":"…","mode":"queue","content":[{"type":"text","text":"…"}]}}}` |
| `session/page` | `request` | `{"args":{"request":{"address":{"kind":"session","sessionId":"…"},"throughSeq":N,"maxMessages":400}}}` |
| `session/cancel` | `request` | `{"args":{"request":{"sessionId":"…"}}}` |
| `session/modelCatalog` | — | `{"args":{}}` |
| `directoryPicker/list` | `path` | `{"args":{"path":"/root"}}` |
| `directoryPicker/createDirectory` | `path` + `name` | |
| `settings/describe` | — | `{"args":{}}` |
| `settings/update` | `ns` | `{"args":{"ns":"agent-default-model"}}` |
| `credentials/describe` | `refs` | `{"args":{"refs":["DEEPSEEK_API_KEY"]}}` |
| `credentials/unset` | `refs` | |
| `llm/listProviders` | — | |
| `subagents/list` | `request` | |
| `workspace/*` | `request` | |
| `agentPresets/copy` | `from` + `id` | |
| `agentPresets/select` | `sessionId` + `agentPreset` | |

> 特别注意：`session/prompt` 的 `requestId` 是**客户端生成**的关联 id（旧版没有这个字段），
> 不填会直接被描述符校验拒绝；`session/page` 的 `throughSeq` 取自 follow 流的 opening cursor。

---

## 三、方法名空间化

`host.describe` **已被删除**（`attachedSessions` 字段在全套包里已无任何引用）。
其余映射见 `ApiCompat.METHOD` 静态表。要点：

| 旧（0.1.1） | 新（0.1.5） |
|---|---|
| `host.listDirectory` / `host.createDirectory` / `host.pickDirectory` | `directoryPicker/list` / `createDirectory` / `pick` |
| `host.openPath` | `session/openWorkspacePath` |
| `session.history` | `session/page` |
| `session.models` / `llm.models` | `session/modelCatalog` |
| `llm.providers` | `llm/listProviders` |
| `agentPreset.{list,read,select,copy,remove}` | `agentPresets/{list,read,select,copy,deletePreset}` |
| `goal.{get,create,edit,pause,resume,complete,clear}` | `goals/{get,create,edit,pause,resume,complete,clear}` |
| `skill.list` | `skills/list` |
| `subagent.{list,prompt,history,interrupt}` | `subagents/{list,prompt,list,interruptByParent}` |
| `settings.openDocument` | `settings/openSettingsDocument` |
| `workspace.list` | 无（用 `session/list`） |

`host.describe` 的替代：本 APP 用 `settings/describe`（读 `agent-default-model` 命名空间拿
provider/model）+ `llm/listProviders` + 固定 guest 路径合成，版本号取自镜像标记文件。

---

## 四、实时事件流（彻底换掉）

### 旧的（0.1.1，dsh-aui 原本对接的）
- `GET /api/events.host`、`GET /api/events.mux` → WS upgrade，
  帧信封 `{"type":"server-request","rpcId":"…","method":"<payload.type>","payload":{…}}`

### 新的（0.1.5）—— 两个路径都已 404
- 全部逻辑流走**单一 WebSocket**：`/api/remote.mux`（握手需要 Cookie）
- 客户端发：
  ```json
  {"type":"open","streamId":"s1","endpoint":"<方法名>","payload":{"args":{...}}}
  {"type":"cancel","streamId":"s1"}
  ```
- 服务端发：
  ```json
  {"type":"item","streamId":"s1","value":<任意 JSON>}
  {"type":"end","streamId":"s1"}
  {"type":"error","streamId":"s1","error":{"code":"…","message":"…","details":{}}}
  ```

实测 `open` 全局事件流（`endpoint:"$events"`）的第一帧：
```json
{"type":"item","streamId":"s1",
 "value":{"type":"ready","clientId":"73fa2162-…","host":{"home":"/root"}}}
```

### 会话实时流：`session/follow`
请求：
```json
{"type":"open","streamId":"f1","endpoint":"session/follow",
 "payload":{"args":{"request":{"address":{"kind":"session","sessionId":"session-…"},
                               "assistantStream":true}}}}
```
实测帧序列（"只回复两个字:你好"）：
```
[1]  {"type":"snapshot","header":{…},"cursor":2,"records":[…],"hasMore":false,"projections":{…}}
[2]  {"type":"event","event":{"type":"agent/inbox/spliced","seq":3,"time":…,"data":{…}}}
[3]  {"type":"event","event":{"type":"turn/start","seq":4,"time":…,"data":{"turn":1}}}
[5]  {"type":"event","event":{"type":"step/start","seq":6,…}}
[6]  {"type":"event","event":{"type":"system/message","seq":7,…}}
[7]  {"type":"event","event":{"type":"user/message","seq":8,…}}
[9]  {"type":"event","event":{"type":"request/header","seq":10,…}}
[11] {"type":"assistant-stream","frame":{"type":"start","attemptId":"…:1","revision":1,"turn":1,"step":1}}
[15] {"type":"assistant-stream","frame":{"type":"chunk","revision":2,"index":0,
       "chunk":{"type":"block-start","index":0,"blockType":"text"}}}
[16] {"type":"assistant-stream","frame":{"type":"chunk","revision":3,"index":1,
       "chunk":{"type":"text-delta","index":0,"text":"你好"}}}
[17] {"type":"assistant-stream","frame":{"type":"chunk","revision":4,"index":2,
       "chunk":{"type":"block-end","index":0,"block":{"type":"text","text":"你好"}}}}
[18] {"type":"assistant-stream","frame":{"type":"chunk","revision":5,"index":3,
       "chunk":{"type":"usage","usage":{"inputTokens":8191,"outputTokens":2,"totalTokens":8193}}}}
[20] {"type":"event","event":{"type":"assistant/message","seq":15,…}}
[22] {"type":"event","event":{"type":"step/end","seq":16,…}}
[23] {"type":"event","event":{"type":"turn/end","seq":17,"data":{"turn":1,"reason":{"kind":"completed"}}}}
```

**映射关系（这正是适配层能成立的原因）**：
- `assistant-stream` + `chunk.type=="text-delta"` ⇒ 旧 UI 的 `assistant/chunk`（`onTextDelta`）
- `event.type=="assistant/message"` 或 `"turn/end"` ⇒ 旧 UI 的流结束（`onStreamEnd`）
- `event` 记录里的 `event.type`（`turn/start`、`step/start`、`user/message`、`session/title`…）
  与旧版的 session 事件类型**同名**，所以旧的事件分发逻辑可以原样复用。

### 控制流
`endpoint:"session/control"`，`payload:{"args":{}}`，下发队列/后台任务/投影的 baseline 与增量。

---

## 五、复现命令

```bash
# 1) 起后端（proroot 内）
cd ~/aptuidsh-rnd && ./pr.sh "$(cat start-dsh.sh)" > dsh-web.log 2>&1 &
TOKEN=$(grep -o 'token=[A-Za-z0-9_-]*' dsh-web.log | tail -1 | cut -d= -f2)

# 2) 换 Cookie
curl -s -c ck.txt -o /dev/null "http://127.0.0.1:3081/?token=$TOKEN"

# 3) 调 API
RID=$(cat /proc/sys/kernel/random/uuid)
curl -s -b ck.txt -X POST http://127.0.0.1:3081/api/session/list \
  -H 'Content-Type: application/json' \
  -d "{\"type\":\"client-request\",\"rpcId\":\"$RID\",\"method\":\"session/list\",\"payload\":{\"args\":{\"_request\":{}}}}"

# 4) 看实时流
cd ~/aptuidsh-rnd && node e2e.mjs      # 建会话 -> follow -> prompt -> 打印全部帧
```

---

## 六、升级 dsh 版本时的检查清单

1. 重新跑 `grep 'case "…": return' client.js`，核对 `ApiCompat.METHOD` 与 args 键名。
2. 确认 `/api/remote.mux` 的 `open/item/end/error` 四种消息仍是这个形状
   （看 `@deepseek-ai/dsh-api-gateway/lib/types/stream-protocol.js`）。
3. 确认 `session/follow` 仍返回 `snapshot` + `event` + `assistant-stream` 三类 item。
4. 确认 `host.describe` 仍不存在（若回归，删掉 `syntheticHostDescribe` 即可）。
5. 确认 Cookie 机制未变（看 `dsh-client-connection/lib/index.js` 的 `BrowserAuth`）。
