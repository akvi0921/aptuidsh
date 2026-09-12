# 模型列表实时性修复(1.14.21)

## 问题现象

会话输出页底部输入卡片的「模型列表」不实时:后端已变更可用模型目录(适配器拓扑变化 /
设置文档提交)后,APP 仍显示旧列表。

实测证据(修复前):本机后端 `llm.models` 实时返回的目录是

| provider | 模型 id | 显示名 |
|---|---|---|
| `deepseek-official` | `deepseek-flash` | DeepSeek-V4.1-Flash |
| `deepseek-official` | `deepseek-v4-pro` | DeepSeek-V4-Pro |
| `qwen-token-plan` | `MiniMax-M2.5` | MiniMax-M2.5 |

而 APP 模型菜单在目录未加载时回退到**内置占位列表**,里面是已下线的旧一代词汇
(`deepseek-v4-flash` / `deepseek-v4-flash-vision-exp`),用户看到的就是这份陈旧清单。

## 官方实现(基准)

`@deepseek-ai/dsh-client-ui-model-selection`(`lib/client.js`)的 `ModelDirectoryResolver`:

```js
ctx.on('connection/reset', () => { for (const d of this.live.directories.values()) d.resetConnected(); });
const refresh = () => { for (const d of this.live.directories.values()) d.load().catch(() => {}); };
ctx.remote.$on('llm/adapters-updated', refresh);        // ← 适配器拓扑变化
ctx.remote.$on('settings/document-updated', refresh);   // ← 设置文档提交
```

要点四条:

1. **订阅两条宿主事件**:`llm/adapters-updated`、`settings/document-updated`,任一到达即重拉目录。
2. **打开菜单即重载**:`ModelSelect.show()` → `reload()` → `directory.load()`,不吃挂载时的旧快照。
3. **代次守卫**:`load()` 里 `const generation = ++this.generation; ... if (generation !== this.generation) return;`
   ——「最新一次操作获胜」,过期响应直接丢弃;`resetConnected()` 先清空再重拉。
4. **失败保留上次成功值**:只有 `result.ok` 才写 store;失败只置 `status:"error"` +
   `error`,UI 渲染错误条与「Retry」,**不清空已有列表**。

事件经 `events.host` 通道转发(见 `dsh-host-apiproxy` 的 `API_REMOTE_FORWARDED_EVENTS` 转发循环):

```json
{"type":"host/remote-event","event":"settings/document-updated","args":["agent-presets",1]}
```

## 根因(修复前的三处缺口)

| # | 缺口 | 后果 |
|---|---|---|
| 1 | `EventStream.dispatchEnvelope` 只把 host 帧转给 UI 监听器,**从未解析 `host/remote-event`** | 后端变更事件全部被丢弃,目录永不刷新 |
| 2 | `onHostEvent` 是空实现;目录只在「加载会话历史」时拉一次,`defaultModels` 还有 `if (isEmpty())` 一次性门闩 | 挂载后不再刷新 |
| 3 | 菜单打开不重拉,且目录为空时回退内置占位列表 | 显示已下线模型,且无加载/错误态 |

其中 #1 是主干:APP 从未订阅这两条事件,所以后端无论怎么变都收不到通知。

## 修复内容

### 1. 新增 `net/ModelCatalogSignals.java`

进程级「目录已失效」信号:EventStream 线程只把版本号 +1(volatile `AtomicLong`),
UI 在主线程比对版本号。**不依赖 Activity 是否存活**——覆盖「在设置页改完模型再回会话页」
与「离线期间发生变更」两种场景。

### 2. `EventStream.dispatchEnvelope` 接入信号

host 帧在分发给监听器**之前**统一过一遍 `ModelCatalogSignals.acceptHostEnvelope(envelope)`。
放在这里而非 UI 层的原因:事件通道在 `MainActivity` 即启动,而 UI 监听器要进会话页才挂载,
目录失效必须在任意时刻都能被记录。

白名单严格限定为官方同名的两条事件,其余 host 帧(`host/session-status` 等)与 mux 帧不受影响。

### 3. `ChatActivity` 三条刷新路径

- **事件驱动**:`LaunchedEffect` 轮询版本号(300ms),变化 → 清空各会话目录缓存 +
  静默重拉当前会话。清缓存是为了避免切到其他会话时读到旧代目录。
- **打开即拉**(官方 load-on-open):`ComposerArea.onModelsReload` → `reloadModelCatalog(currentId)`。
- **投影帧**:新增消费 `modelSelection` 投影(此前被 `when(key)` 忽略),任何路径
  (Web UI / 命令 / 其他客户端)改模型都能回写显示并触发一次目录重拉。

### 4. `reloadModelCatalog()` 逐条对齐官方不变量

- 代次守卫:`val gen = ++modelLoadGen`,响应回来时不等于当前代次即丢弃 —— 防
  「打开菜单触发的重拉」与「切换会话触发的重拉」交错时旧目录覆盖新目录。
- 失败保留上次成功值:只有 `value != null` 才写目录;失败只置 `modelsError`。
- 成功时同步 `current`(provider/model/effort):他处改模型也能收敛。
- 空目录如实反映后端状态,**不再回退占位列表**。

### 5. UI 状态面(`Composer.kt`)

- 菜单打开时触发重拉。
- 菜单内「正在刷新模型列表…」状态行;错误条 + 「重试」(官方 `error.action` + Retry)。
- 移除内置占位目录;**推理档子菜单同样不再回退硬编码 `off/low/high/max`**,
  无档位时显示「当前模型未提供推理等级。」(官方 `empty.efforts`)。
- `sessionSelectModel` 失败改为 Toast + 错误条回显并撤销乐观更新(此前 `onError` 为空,
  静默失败会让用户以为「选了但没生效」)。

## 验证

1. **后端实测帧**:订阅 `events.host` → 发起一次真实 `settings.update`(值确有变化),
   捕获到 `{"type":"host/remote-event","event":"settings/document-updated","args":["agent-presets",1]}`;
   同时确认**同值写入不发事件**(`bumpRevision` 的 `deepEqualJson` 短路),即该通道确为事件驱动。
   验证后已用 `settings.mutate` unset 探针键还原 `agent-presets` 为 `{"default":"android-dev"}`。
2. **解析器单测**(11/11 通过,使用真实 `org.json` + 逐字实测帧):
   真实两条事件被消费、白名单外事件忽略且版本号不动、`host/session-status` 与
   `session/projection` 不误判、null/缺 payload/非对象/缺 event 均安全。
3. **目录解析对齐**:`parseModels` 等价实现跑后端真实 `llm.models` 响应 →
   3 条目(含 `deepseek-flash` 与 `MiniMax-M2.5`),旧占位词条不再出现。
4. 构建:1.14.21(versionCode 102)编译通过。

## 影响面

- 改动文件:`net/ModelCatalogSignals.java`(新增)、`net/EventStream.java`、
  `ChatActivity.kt`、`ui/Composer.kt`、`ui/ComposerArea.kt`、`app/build.gradle`。
- 不改协议、不改依赖、不触碰会话流折叠与审批/提问链路。
- 目录为空时菜单现在显示「没有可用的模型。」而不是占位清单 —— 这是刻意行为:
  宁可如实报空,也不展示后端已不提供的模型。
