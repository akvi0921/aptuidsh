# 工作区弹窗：AbortSignal.any is not a function

## 现象

原生 UI「添加工作区」弹窗打开后列表为空，红字报错：

```
AbortSignal.any is not a function
```

导致无法创建工作区 → 无法新建会话（UI 要求先选工作区）。

## 已排除的可能（均为实测）

| 假设 | 实测结果 |
|---|---|
| guest 里的 Node 缺 `AbortSignal.any` | ❌ 排除。`./pr.sh node -e "typeof AbortSignal.any"` → `function`，且调用成功（Node v22.22.2） |
| `directoryPicker/list` 入参形态不对 | ❌ 排除。`{}`、`{path:"/root"}`、`{path:""}`、`{path:"/sdcard"}` 全部返回正常响应（成功或合理的 unreadable 错误） |
| 服务端 `$events` 流出问题 | ❌ 排除。模拟 `open $events` → 正常收到 `ready` 帧 |
| 后端整体不可用 | ❌ 排除。`workspace/create` 与 `session/create` 均返回 `ok:true` |
| 服务端 `AbortSignal.any` 被 polyfill 覆盖 | ❌ 排除。全包 grep 无赋值/兜底写法；唯一调用点集中在 3 个文件的 3 行 |

服务端 `AbortSignal.any` 的全部调用点：
```
dsh-api-gateway/lib/index.js:589       openRemoteEvents（$events 流）
dsh-agent-loop/lib/index.js:1889       回合启动时的信号融合
dsh-llm-deepseek/lib/index.js:1627     LLM 空闲看门狗
```

## 本轮采取的两条措施

### 1. 兜底通路（立刻可用）

`directoryPicker/list` 与 `workspace/create` 是**两条独立链路**。既然后者实测可用，
弹窗在目录列举失败时**自动回退到默认工作区**并把路径填好，用户直接点「打开」即可创建成功：

```
默认工作区 = /sdcard/APTUIDSH（手机共享存储，用户可见）
           ↘ /root/workspace（手机存储不可写时回退）
```

这样即便列举问题未根治，也不会再卡住「无法建会话」的死循环。

### 2. RPC 明细日志（定位根因）

`DshClient` 增加请求/响应日志：

```
RPC → <本地方法名> → <实际发出的方法> args=<参数>
RPC ← <方法> ok <结果>          /          RPC ← <方法> 失败 <服务端原始错误对象>
```

下次复现时，控制台会直接显示**到底是哪个方法失败、发出的参数是什么、服务端原样返回的错误码与消息**，
无需再靠推理。

---

# 同一族错误：换机器后 `signal?.throwIfAborted is not a function`

## 现象

同一份 APK（1.4.0）：

| 机器 | 系统 WebView | 结果 |
|---|---|---|
| 华为 P50 Pro | `Chrome/114.0.5735.196` | 一切正常 |
| **另一台手机** | 旧于 Chrome 100 | 打开官方 Web UI 后**无法正常会话** |

那台机器上的红字（来自「Agent 预设」「模型」等页面的 client api 调用）：

```
client api: agentPresets/list failed:
signal?.throwIfAborted is not a function

加载提供商目录失败: client api: llm/listProviders failed:
signal?.throwIfAborted is not a function
```

## 这条报错自身就说明了性质

`signal?.throwIfAborted is not a function` —— 注意那个 `?.`。

`?.` 只保护「`signal` 本身是 `undefined/null`」这一种情况，**不保护「方法不存在」**。
如果 `signal` 真的是 `undefined`，`?.` 会直接短路跳过、根本不调用，报错形式也会是
`Cannot read properties of undefined (reading 'throwIfAborted')`。

所以能报出这个形式，说明：**`signal` 是一个真实对象，缺的是它上面的 `throwIfAborted` 方法**
——即该内核没有实现这个 API。

## 它在最要命的路径上

`dsh-client-connection/lib/client.js`（0.1.7-rc.1 实测，第 1216~1231 行附近）
——**所有 client api RPC 的必经之路**：

```js
const response = await send(`${channel}/${endpoint}`.slice(1), {
  method: "POST", headers: { "content-type": "application/json" },
  body: JSON.stringify(message),
  ...signal === void 0 ? {} : { signal }
});
if (!response.ok) throw new Error(`transport failure ... HTTP ${response.status}`);
const full = ... parseConnectionResponse(await response.json());   // ← 响应已成功解析
signal?.throwIfAborted();                                          // ← 抛在这一行
if (full.rpcId !== rpcId) throw new Error(`rpcId mismatch for ${endpoint}: ...`);
return full.result;
```

关键在于顺序：**HTTP 请求已经 200、`response.ok` 检查已通过、响应体已经解析完**，
下一行才抛。所以：

- **服务端是好的** —— 请求确实被正确处理了，只是结果回不到界面；
- **不是网络/后端/会话数据的问题**；
- **不是 arm64 / proroot / rootfs 的问题** —— WebUI 能起、能渲染、RPC 能拿到 200，
  说明 proroot + Ubuntu + Node + dsh 整条链是通的。

`throwIfAborted` 在**10 个客户端包**里被调用（`dsh-api-gateway`、`dsh-client-connection`、
`dsh-api-session-controller`、`dsh-api-terminal-controller`、`dsh-client-ui-workspace`、
`dsh-client-ui-skill`、`dsh-client-ui-reference`、`dsh-client-ui-sidebar-browser`、
`dsh-client-ui-sidebar-documentpreview`、`dsh-experimental-client-ui-voice-input`），
所以**所有** `client api:` 调用一起失败（截图里 `agentPresets/list` 与 `llm/listProviders`
同时挂就是这个原因），而 **sessions 走的也是同一条路** —— 这就是「无法正常会话」的由来。
不依赖 client api 的静态界面照常渲染。

## 版本要求（MDN browser-compat-data 实测）

| 成员 | Chrome 起始版本 | 本仓垫片是否覆盖 |
|---|---|---|
| **`AbortSignal.throwIfAborted`** | **100** | ❌ **未覆盖** |
| `AbortSignal.any` | 116 | ✅ |
| `AbortSignal.timeout` | 103（语义修正于 124） | ✅ |

`AbortSignal.throwIfAborted` 是 Chrome 100 的老 API，因此报出这个错误，就等价于
**那台机器的 WebView 版本 < 100**。

## 根因：兼容层的覆盖窗口是按一台设备标定的

垫片的覆盖窗口是 **(114, ∞)**，而真正的需求是 **(该机版本, ∞)**。

Chrome 100 **小于** 114，所以在 P50 Pro（114）上这个 API 是**原生自带**的，
从来没被当成缺口；一旦换到 WebView < 100 的机器，它就正好掉进
**(该机版本, 114]** 这条缝里，没有任何兜底。

测试基线也印证了这个隐含假设——`tools/polyfill-test` 里「模拟 Chrome 114」
删掉的 27 个 API **全部落在 >114 档**（`Iterator.*`@122、`Set` 的 7 个集合运算@122、
`Object/Map.groupBy`@117、`Promise.try`@128、`Promise.withResolvers`@119、
`RegExp.escape`@136、`URL.parse`@126、`Math.sumPrecise`@147、`*.bytes()`@132~144 等），
**没有任何一个是 100~114 档的**。

## 已排除的可能（均为实测）

| 假设 | 实测结果 |
|---|---|
| 本项目垫片把 `AbortSignal` 弄坏了 | ❌ 排除。垫片在该对象上只加两个**静态**方法（`any`/`timeout`），都带 `typeof !== 'function'` 守卫，**从不触碰 `AbortSignal.prototype`**；且 `any` 在 Chrome 114 上也缺失，在**能用**的那台机器上同样会装 —— 装了也不影响 `throwIfAborted`。垫片源码里 `throwIfAborted` 出现 **0 次** |
| 后端/会话数据有问题 | ❌ 排除。错误发生在响应解析**之后**，服务端已是成功路径 |
| arm64 / proroot / rootfs 不适配 | ❌ 排除。WebUI 能起能渲染、RPC 能拿到 200 |
| 网络不通 | ❌ 排除。同一条 RPC 已经往返成功 |

## 同一档（Chrome 100~114）还有没有别的坑

把 BCD 里 **Chrome 100~114 新增的「独特命名」成员**与 63 个客户端 bundle 求交集，
除 `throwIfAborted`（10 个包）外只剩几处低频、且大概率在特性检测后面的：

| 调用 | 引入版本 | 出现位置 |
|---|---|---|
| `Navigation.canGoBack/canGoForward` | 102 | 仅 `dsh-client-ui-sidebar-browser` |
| `WindowControlsOverlay.getTitlebarAreaRect` | 105 | 仅终端页 |
| `ML.createContext`（WebNN） | 112 | 仅会话/Excel 预览 |

> 注意：若用宽泛关键词扫描，会命中一堆 `.clear()` / `.json()` / `.value()` / `.concat()`
> 之类 —— 那些是**与无关 BCD 成员同名碰撞的假阳性**（例如匹配到 `Highlight.clear`、
> `CSSMathClamp.value`、`MLGraphBuilder.concat`），不足为据。所以那一档目前暴露出来的
> 主要痛点就是 `throwIfAborted` 这一个。

## 一步确认

「环境控制台」的运行日志里有这一行：

```
WebView UA: Mozilla/5.0 (Linux; Android …) … Chrome/xx.x.xxxx.xx Mobile Safari/537.36
```

那台机器上这个数字就是答案：**< 100 即完全吻合**。

## 结论

`X is not a function` 这一族错误（本文件记录了两例：`AbortSignal.any`（旧自研前端的
服务端调用）与 `signal?.throwIfAborted`（官方 Web UI 的客户端 RPC））**绝大多数都不是
传参或后端问题，而是「这台机器的内核比兼容层的标定版本更旧」**。

排查这类错误时按三步走：

1. 看报错形式——带 `?.` 的「is not a function」说明是**对象在、方法缺**，先怀疑内核版本；
2. 查 MDN/BCD 得到该成员的起始内核版本，与设备 UA 里的版本比对；
3. 若版本落在 **(该机版本, 已标定基线]** 区间，就是覆盖窗口的缝隙，而不是代码 bug。
