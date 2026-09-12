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

---

## 七、审批 / 提问的应答（`/api/respond` 已废弃）

### 现象
```
$ curl -X POST -b ck.txt http://127.0.0.1:3081/api/respond -d '{}'
HTTP/1.1 404 Not Found
```
且全套 dsh 包里已搜不到 `client-response` 字样——旧回执信封被整体移除。

### 新机制（实测 + 类型定义双确认）
待应答的请求以 **waterfall 帧**经 `$events` 下发，实测原样报文：
```json
{"type":"item","streamId":"dsh-ev-host","value":{
   "type":"waterfall",
   "event":"user-questions/request",
   "eventId":"7687321d-d986-4fd0-870d-7c65d10d9cfe",
   "agentId":"session-4eb800f9-531b-4c90-8ea6-131d5912a0ad",
   "request":{"questions":[{"id":"color","question":"你喜欢什么颜色？",
                            "header":"颜色偏好",
                            "options":[{"label":"红色"},{"label":"蓝色"}]}]}}}
```

应答 = 一次普通 HTTP RPC，端点 `$events/result`：
```json
POST /api/$events/result
{"args":{"clientId":"<ready 帧的 clientId>",
         "eventId":"7687321d-…",
         "outcome":{"kind":"result","value":{"answers":[{"id":"color","selected":["蓝色"]}]}}}}
```
- `outcome.kind` ∈ `next`（委派给下一个应答者）| `result`（给出结论）| `rejected`
- **提问**的 `value` 就是 `AskUserQuestionAnswer = {answers:[{id, selected:[], custom?}]}`
- **审批**的 `value` 是闭集字符串 `ApprovalOutcome = 'allowed-once' | 'rejected' | 'cancelled' | 'unavailable'`
  （注意不是 "approved"，`allowed-once` 才是授权）

### 端到端实测（本文档写作时跑通）
```
[prompt]  请用 ask_user_question 问我一个问题…
[waterfall] eventId=7687321d-…  questions=[{id:color,…}]
[answer]   POST /api/$events/result → HTTP 200 {"result":{"ok":true}}
[agent]    你选择的颜色是蓝色。          ← agent 确实收到了答案
[turn/end] {"kind":"completed"}
```

### APP 侧实现
- `WaterfallRegistry`：登记 `clientId`（ready 帧）与待应答的 `eventId → {event, agentId, request}`；
  连接代切换时清空（旧 eventId 跨连接失效，但服务端会在新连接上**重放**仍待处理的请求）。
- `EventStream.dispatchWaterfall()`：把 `user-questions/request` / `approval/request`
  翻译成旧 UI 认的 `question/requested` / `approval/requested` mux 帧，
  并把 `eventId` 同时写进信封的 `rpcId`（旧 UI 正是拿 rpcId 当应答凭据）。
- `DshClient.respond()`：把旧式载荷映射成 `$events/result` 的 args，
  审批 outcome 做取值归一（`approved` → `allowed-once` 等），成功即从登记表移除。

---

## 八、会话历史的取法（`session/page` 的游标限制）

`session/page` 要求 `throughSeq` **不得超过会话当前游标**，实测超限报错：
```json
{"ok":false,"error":{"code":"gateway/bad-request",
 "message":"session page through seq 2147483647 is past cursor 2"}}
```
而游标只在 `session/follow` 的 opening snapshot 里下发（字段 `cursor`）。
因此 APP 侧把 `session.history` 实现为**一次性的 follow 流取快照**（`net/MuxClient.java`：
开一条逻辑流 → 读第一个 item → 关闭），再把 `records[].event` 还原成旧版
`{events:[…], hasMore}` 事件账本，上层 UI 零改动。
这与官方客户端「先 follow 拿快照、再按 beforeSeq 往前翻页」的做法一致。

---

## 九、适配层缺陷表（由 `tools/jvmtest` 实机测试台抓出并修复）

`tools/jvmtest/run.sh` 会直接编译运行 APP 里**真实的** `ApiCompat.java` 与
`MuxClient.java`，把生成的请求逐个打到真实后端。首轮跑出 8 项失败，其中 5 项是真缺陷：

| # | 缺陷 | 服务端原始报错 | 后果 | 修复 |
|---|---|---|---|---|
| 1 | `MuxClient` 用 `new URL("ws://…")` 解析地址 | `java.net.MalformedURLException: unknown protocol: ws` | **会话历史必然崩溃**（java.net.URL 没有 ws 协议处理器） | 保持 http 形式解析 host/port，握手只用这两个值 + 固定路径 |
| 2 | `goals/*` 用 `sessionId` 寻址 | `missing "agentId"; unexpected "sessionId"` | 目标（goal）功能全废 | 改用 `agentId` |
| 3 | `subagents/list` 把参数包进 `request` | `missing "parentSessionId"; unexpected "request"` | 子代理列表全废 | 改为扁平 `parentSessionId` |
| 4 | `commands/list` 又包了一层 `args` | `missing "agentId"; unexpected "args"` | 斜杠命令列表全废 | 平铺内层对象；`images` → `submittedAttachments` |
| 5 | `llm/discoverModels` 只传 `settingsNs` | `missing "request"` | 模型发现不可用 | 补 `request` 对象 |

另外 3 项是**测试台自身或部署能力**的问题，非适配层缺陷：
- `session.search`：该部署把会话查询索引配成 `openAt: "never"`，搜索被显式禁用；
- `llm/discoverModels` 修好形状后仍报 `llm/model-discovery-rejected`——
  错误码已从 `gateway/arguments-invalid` 变为领域错误，说明 schema 已正确，
  是该命名空间没有注册模型发现能力；
- `session.history` 在 `ApiCompat` 里必须标成**合成方法**
  （`$synthetic/session.history`），否则方法表与实际实现不一致，容易埋雷。

最终状态：**23 项全部通过**（含 N/A 标注）。

### 教训
方法名与形参名的漂移肉眼极难发现，而一旦写错，在真机上的表现是「某个功能静默失效」。
因此**升级 dsh 版本后第一件事就是跑这个测试台**。
