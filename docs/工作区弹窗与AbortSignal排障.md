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
