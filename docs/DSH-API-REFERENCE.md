# dsh API 完整参考文档

> 本文档详细列出 dsh (DeepSeek Harness) 通过 HTTP/WebSocket 对外暴露的所有接口。
> 基于 dsh 官方源码分析，适用于一体化 APP 开发时参考。

---

## 目录

1. [基础架构](#一基础架构)
2. [HTTP RPC 接口](#二http-rpc-接口)
3. [WebSocket 接口](#三websocket-接口)
4. [数据交换格式](#四数据交换格式)
5. [处理逻辑流程](#五处理逻辑流程)
6. [特权方法](#六特权方法)
7. [配置项](#七配置项)

---

## 一、基础架构

### 1.1 服务器架构

```
┌─────────────────────────────────────────────────────────────┐
│                    dsh-host-webserver                        │
├─────────────────────────────────────────────────────────────┤
│  HTTP Server (node:http)                                    │
│  - 监听端口: 3080 (默认)                                    │
│  - 绑定地址: 127.0.0.1 (仅本机回环)                        │
├─────────────────────────────────────────────────────────────┤
│  路由系统:                                                   │
│  - exact: 精确路径匹配                                      │
│  - prefixes: 前缀匹配 (最长前缀优先)                        │
│  - upgrades: WebSocket 升级路由                             │
│  - fallback: 默认处理 (SPA 静态文件)                        │
├─────────────────────────────────────────────────────────────┤
│  插件化架构 (Cordis 框架)                                   │
│  - 每个功能模块作为独立插件注册                              │
│  - 支持动态路由注册/注销                                    │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 通信协议

| 协议 | 路径 | 用途 | 传输方式 |
|------|------|------|---------|
| HTTP POST | `/api/<method>` | RPC 调用 | JSON-RPC 2.0 |
| WebSocket | `/api/events.mux` | 会话事件流 | 双向帧 |
| WebSocket | `/api/events.host` | 主机事件流 | 双向帧 |
| HTTP GET | `/*` | 静态文件 | SPA fallback |

---

## 二、HTTP RPC 接口

### 请求格式

```http
POST /api/<method> HTTP/1.1
Content-Type: application/json

{
  "type": "client-request",
  "rpcId": "unique-request-id",
  "method": "method.name",
  "payload": { ... }
}
```

### 响应格式

```json
{
  "type": "server-response",
  "rpcId": "unique-request-id",
  "result": {
    "ok": true,
    "value": { ... }
  }
}
```

错误响应:
```json
{
  "type": "server-response",
  "rpcId": "unique-request-id",
  "result": {
    "ok": false,
    "error": {
      "code": "error-code",
      "message": "Human-readable error message"
    }
  }
}
```

---

### 2.1 Session 会话管理

#### `session.list` - 获取会话列表

**功能**: 获取当前所有会话的列表，包括会话基本信息、工作区归属、运行状态等。

**请求参数**: `{}` (无参数)

**返回值**:
```json
{
  "sessions": [
    {
      "id": "session-uuid",
      "title": "会话标题",
      "workspaceId": "workspace-uuid",
      "agentPreset": "preset-id",
      "createdAt": "2024-01-01T00:00:00Z",
      "updatedAt": "2024-01-01T00:00:00Z",
      "running": false,
      "blank": false
    }
  ]
}
```

**使用场景**: 
- 侧边栏显示会话列表
- 会话切换
- 会话搜索的基础数据

---

#### `session.search` - 搜索会话

**功能**: 根据关键词搜索会话标题、工作区名称等。

**请求参数**:
```json
{
  "query": "搜索关键词"
}
```

**返回值**: 同 `session.list`，仅返回匹配的会话。

**使用场景**: 
- 侧边栏搜索功能
- 快速定位会话

---

#### `session.create` - 创建新会话

**功能**: 创建一个新的空白会话，可选择关联工作区和预设。

**请求参数**:
```json
{
  "workspaceId": "optional-workspace-id",
  "agentPreset": "optional-preset-id"
}
```

**返回值**:
```json
{
  "sessionId": "new-session-uuid"
}
```

**使用场景**: 
- 点击"新建会话"按钮
- 从引导层创建会话
- 自动创建会话（启动时无会话）

---

#### `session.history` - 获取会话历史

**功能**: 获取指定会话的历史消息列表，包括用户消息、助手回复、工具调用等。

**请求参数**:
```json
{
  "sessionId": "session-uuid",
  "limit": 50
}
```

**返回值**:
```json
{
  "messages": [
    {
      "id": "message-uuid",
      "role": "user|assistant",
      "content": [
        { "type": "text", "text": "消息内容" },
        { "type": "tool-call", "toolCallId": "...", "name": "...", "arguments": {} },
        { "type": "tool-result", "toolCallId": "...", "content": {} }
      ],
      "source": { "kind": "user|model|tool" },
      "timestamp": "2024-01-01T00:00:00Z"
    }
  ],
  "projections": {
    "permissions": { ... },
    "sessionStats": { ... },
    "contextPressure": { ... }
  }
}
```

**使用场景**: 
- 打开会话时加载历史消息
- 消息流渲染
- 上下文恢复

---

#### `session.models` - 获取会话可用模型

**功能**: 获取指定会话可用的模型列表，包括当前选中的模型、各提供方的模型分组等。

**请求参数**:
```json
{
  "sessionId": "session-uuid"
}
```

**返回值**:
```json
{
  "current": {
    "provider": "deepseek-official",
    "model": "deepseek-v4-flash",
    "reasoningEffort": "high"
  },
  "routable": true,
  "groups": [
    {
      "id": "deepseek-official",
      "name": "DeepSeek",
      "models": [
        {
          "id": "deepseek-v4-flash",
          "name": "DeepSeek-V4-Flash",
          "reasoning": {
            "efforts": [
              { "id": "low", "name": "Low" },
              { "id": "medium", "name": "Medium" },
              { "id": "high", "name": "High" }
            ],
            "defaultEffort": "high"
          }
        }
      ]
    }
  ],
  "failures": []
}
```

**使用场景**: 
- 输入卡片的模型选择器
- 显示当前使用的模型
- 切换模型

---

#### `session.selectModel` - 选择模型

**功能**: 为指定会话选择使用的模型和推理档位。

**请求参数**:
```json
{
  "sessionId": "session-uuid",
  "provider": "deepseek-official",
  "model": "deepseek-v4-flash",
  "reasoningEffort": "high"
}
```

**返回值**: `{}` (成功即为空)

**使用场景**: 
- 用户在模型选择器中切换模型
- 设置默认模型

---

#### `session.rename` - 重命名会话

**功能**: 修改会话的显示标题。

**请求参数**:
```json
{
  "sessionId": "session-uuid",
  "title": "新的会话标题"
}
```

**返回值**: `{}`

**使用场景**: 
- 用户手动重命名会话
- 自动根据首条消息生成标题

---

#### `session.fork` - 分叉会话

**功能**: 从指定会话的某个时间点分叉出新的会话，保留历史消息。

**请求参数**:
```json
{
  "sessionId": "session-uuid",
  "atSeq": 123
}
```

**返回值**:
```json
{
  "childSessionId": "new-child-session-uuid"
}
```

**使用场景**: 
- 点击消息的"分支"按钮
- 从历史某点重新开始对话

---

#### `session.prompt` - 发送提示

**功能**: 向会话发送用户消息，触发 AI 回复。支持排队和插话两种模式。

**请求参数**:
```json
{
  "sessionId": "session-uuid",
  "content": [
    {
      "type": "text",
      "text": "用户输入的消息"
    }
  ],
  "mode": "queue"
}
```

`mode` 可选值:
- `queue`: 排队等待（默认）- 等待当前回复完成后处理
- `steer`: **插话/引导** - 立即进入助手上下文，影响当前生成过程

**插话 (steer) 功能说明**:
- 当助手正在输出时，用户可以立即输入消息
- 消息会立即进入助手的上下文，影响当前的生成过程
- 助手会感知到新的用户输入并调整生成方向
- 插话消息在队列中显示为 "steering" 状态

**返回值**:
```json
{
  "accepted": true,
  "command": {  // 可选，如果是斜杠命令
    "kind": "success",
    "text": "命令执行结果"
  }
}
```

**错误情况**:
- `steer-unavailable`: 当前 turn 已结束，无法插话
- `agent-busy`: 代理忙，无法处理
- `attachment-error`: 附件错误

**使用场景**: 
- 用户发送消息
- 触发 AI 生成回复
- **插话**: 助手输出时立即纠正/补充/引导

---

#### `session.attachment` - 上传附件

**功能**: 向会话上传文件附件（图片、文档等）。

**请求参数**:
```json
{
  "sessionId": "session-uuid",
  "filename": "image.png",
  "data": "base64-encoded-data"
}
```

**返回值**:
```json
{
  "attachmentId": "attachment-uuid"
}
```

**使用场景**: 
- 图片识别
- 文档分析
- 文件上传

---

#### `session.updateQueue` - 更新会话队列

**功能**: 更新会话的消息队列（如取消待处理的消息）。

**请求参数**:
```json
{
  "sessionId": "session-uuid",
  "operations": [
    { "op": "remove", "messageId": "message-uuid" }
  ]
}
```

**返回值**: `{}`

**使用场景**: 
- 取消排队中的消息
- 管理消息队列

---

#### `session.cancel` - 取消会话

**功能**: 取消当前正在进行的 AI 回复。

**请求参数**:
```json
{
  "sessionId": "session-uuid"
}
```

**返回值**: `{}`

**使用场景**: 
- 点击"停止"按钮
- 用户中断 AI 生成

---

### 2.2 Subagent 子代理

#### `subagent.list` - 列出子代理

**功能**: 获取指定会话的所有子代理列表。

**请求参数**:
```json
{
  "sessionId": "session-uuid"
}
```

**返回值**:
```json
{
  "subagents": [
    {
      "id": "subagent-uuid",
      "parentId": "parent-session-uuid",
      "status": "running|completed|failed",
      "objective": "子代理任务目标"
    }
  ]
}
```

**使用场景**: 
- 显示子代理状态
- 子代理管理

---

#### `subagent.history` - 获取子代理历史

**功能**: 获取子代理的对话历史。

**请求参数**:
```json
{
  "subagentId": "subagent-uuid"
}
```

**返回值**: 同 `session.history`

**使用场景**: 
- 查看子代理执行过程
- 调试子代理任务

---

#### `subagent.prompt` - 提示子代理

**功能**: 向子代理发送消息。

**请求参数**:
```json
{
  "messageId": "message-uuid"
}
```

**返回值**: `{}`

**使用场景**: 
- 与子代理交互
- 提供额外信息

---

#### `subagent.interrupt` - 中断子代理

**功能**: 中断正在运行的子代理。

**请求参数**:
```json
{
  "subagentId": "subagent-uuid"
}
```

**返回值**: `{}`

**使用场景**: 
- 停止不需要的子代理
- 超时中断

---

### 2.3 Host 主机管理

#### `host.describe` - 描述主机

**功能**: 获取 dsh 主机的基本信息，包括版本、当前模型、工作目录等。

**请求参数**: `{}`

**返回值**:
```json
{
  "version": "0.x.x",
  "provider": "deepseek-official",
  "model": "deepseek-v4-flash",
  "cwd": "/data/data/com.termux/files/home",
  "home": "/data/data/com.termux/files/home",
  "attachedSessions": ["session-uuid-1", "session-uuid-2"]
}
```

**使用场景**: 
- 首屏显示后端信息
- 连接状态检查
- 版本显示

---

#### `host.pickDirectory` - 选择目录

**功能**: 弹出目录选择器，让用户选择一个目录。

**请求参数**:
```json
{
  "title": "选择工作目录"
}
```

**返回值**:
```json
{
  "path": "/selected/directory/path"
}
```

**使用场景**: 
- 工作区创建时选择路径
- 文件导入

---

#### `host.listDirectory` - 列出目录

**功能**: 列出指定目录下的文件和子目录。

**请求参数**:
```json
{
  "path": "/directory/to/list"
}
```

**返回值**:
```json
{
  "entries": [
    { "name": "file.txt", "type": "file", "size": 1234 },
    { "name": "subdir", "type": "directory" }
  ]
}
```

**使用场景**: 
- 文件浏览器
- 工作区目录选择

---

#### `host.createDirectory` - 创建目录

**功能**: 创建新的目录。

**请求参数**:
```json
{
  "path": "/path/to/new/directory"
}
```

**返回值**: `{}`

**使用场景**: 
- 创建工作区目录
- 创建项目文件夹

---

#### `host.openPath` - 打开路径

**功能**: 用系统默认应用打开文件或目录。

**请求参数**:
```json
{
  "path": "/path/to/open"
}
```

**返回值**: `{}`

**使用场景**: 
- 打开文件
- 在文件管理器中显示

---

### 2.4 Workspace 工作区

#### `workspace.list` - 列出工作区

**功能**: 获取所有工作区的列表，包括工作区名称、路径、包含的会话等。

**请求参数**: `{}`

**返回值**:
```json
{
  "workspaces": [
    {
      "id": "workspace-uuid",
      "name": "工作区名称",
      "path": "/path/to/workspace",
      "sessionIds": ["session-1", "session-2"],
      "createdAt": "2024-01-01T00:00:00Z"
    }
  ]
}
```

**使用场景**: 
- 侧边栏工作区列表
- 工作区选择器
- 会话分组

---

#### `workspace.create` - 创建工作区

**功能**: 创建新的工作区。

**请求参数**:
```json
{
  "name": "新工作区",
  "path": "/optional/path"
}
```

**返回值**:
```json
{
  "workspaceId": "new-workspace-uuid"
}
```

**使用场景**: 
- 创建新工作区
- 导入项目

---

#### `workspace.rename` - 重命名工作区

**功能**: 修改工作区名称。

**请求参数**:
```json
{
  "workspaceId": "workspace-uuid",
  "name": "新名称"
}
```

**返回值**: `{}`

**使用场景**: 
- 用户重命名工作区

---

#### `workspace.delete` - 删除工作区

**功能**: 删除工作区（不会删除会话）。

**请求参数**:
```json
{
  "workspaceId": "workspace-uuid"
}
```

**返回值**: `{}`

**使用场景**: 
- 删除不需要的工作区

---

#### `workspace.insertBefore` - 插入工作区位置

**功能**: 调整工作区在列表中的顺序。

**请求参数**:
```json
{
  "workspaceId": "workspace-uuid",
  "beforeId": "target-workspace-uuid"
}
```

**返回值**: `{}`

**使用场景**: 
- 工作区排序

---

#### `workspace.insertSessionBefore` - 插入会话到工作区

**功能**: 将会话移动到指定工作区。

**请求参数**:
```json
{
  "workspaceId": "workspace-uuid",
  "sessionId": "session-uuid",
  "beforeId": "target-session-uuid"
}
```

**返回值**: `{}`

**使用场景**: 
- 会话归类
- 拖拽会话到工作区

---

#### `workspace.archiveSession` - 归档会话

**功能**: 将会话从工作区归档（移出工作区但保留会话）。

**请求参数**:
```json
{
  "workspaceId": "workspace-uuid",
  "sessionId": "session-uuid"
}
```

**返回值**: `{}`

**使用场景**: 
- 归档完成的会话
- 清理工作区

---

### 2.5 Skill 技能

#### `skill.list` - 列出技能

**功能**: 获取所有可用技能（工具）的列表。

**请求参数**: `{}`

**返回值**:
```json
{
  "skills": [
    {
      "id": "skill-id",
      "name": "技能名称",
      "description": "技能描述",
      "enabled": true
    }
  ]
}
```

**使用场景**: 
- 显示可用工具
- 工具管理

---

### 2.6 Agent Preset 预设

#### `agentPreset.list` - 列出预设

**功能**: 获取所有 Agent 预设的列表。

**请求参数**: `{}`

**返回值**:
```json
{
  "presets": [
    {
      "id": "standard",
      "name": "标准模式",
      "description": "功能完整的编码 Agent",
      "builtin": true
    }
  ]
}
```

**使用场景**: 
- 设置页面的预设选择
- 新会话的默认预设

---

#### `agentPreset.select` - 选择预设

**功能**: 为指定会话选择 Agent 预设。

**请求参数**:
```json
{
  "sessionId": "session-uuid",
  "agentPreset": "preset-id"
}
```

**返回值**: `{}`

**使用场景**: 
- 切换会话的 Agent 模式
- 应用默认预设

---

#### `agentPreset.read` - 读取预设

**功能**: 获取指定预设的详细配置。

**请求参数**:
```json
{
  "presetId": "preset-id"
}
```

**返回值**:
```json
{
  "preset": {
    "id": "standard",
    "name": "标准模式",
    "description": "...",
    "systemPrompt": "...",
    "tools": [...]
  }
}
```

**使用场景**: 
- 预设详情查看
- 预设编辑

---

#### `agentPreset.copy` - 复制预设

**功能**: 复制现有预设创建新预设。

**请求参数**:
```json
{
  "presetId": "source-preset-id",
  "newName": "我的预设"
}
```

**返回值**:
```json
{
  "newPresetId": "new-preset-uuid"
}
```

**使用场景**: 
- 基于现有预设创建自定义预设

---

#### `agentPreset.openDocument` - 打开预设文档

**功能**: 用编辑器打开预设的配置文件。

**请求参数**:
```json
{
  "presetId": "preset-id"
}
```

**返回值**: `{}`

**使用场景**: 
- 高级用户编辑预设配置

---

#### `agentPreset.remove` - 删除预设

**功能**: 删除自定义预设（内置预设不可删除）。

**请求参数**:
```json
{
  "presetId": "preset-id"
}
```

**返回值**: `{}`

**使用场景**: 
- 删除不需要的预设

---

### 2.7 Goal 目标

#### `goal.create` - 创建目标

**功能**: 创建一个持久化目标，AI 会在多轮对话中持续推进。

**请求参数**:
```json
{
  "sessionId": "session-uuid",
  "objective": "目标描述",
  "maxGoalRounds": 10
}
```

**返回值**:
```json
{
  "goalId": "goal-uuid"
}
```

**使用场景**: 
- 长期任务跟踪
- 多步骤项目

---

#### `goal.edit` - 编辑目标

**功能**: 修改目标描述或最大轮次。

**请求参数**:
```json
{
  "goalId": "goal-uuid",
  "objective": "更新后的目标",
  "maxGoalRounds": 15
}
```

**返回值**: `{}`

**使用场景**: 
- 调整目标范围

---

#### `goal.pause` - 暂停目标

**功能**: 暂停目标执行。

**请求参数**:
```json
{
  "goalId": "goal-uuid"
}
```

**返回值**: `{}`

**使用场景**: 
- 暂时停止任务

---

#### `goal.resume` - 恢复目标

**功能**: 恢复暂停的目标。

**请求参数**:
```json
{
  "goalId": "goal-uuid"
}
```

**返回值**: `{}`

**使用场景**: 
- 继续执行任务

---

#### `goal.complete` - 完成目标

**功能**: 标记目标为已完成。

**请求参数**:
```json
{
  "goalId": "goal-uuid"
}
```

**返回值**: `{}`

**使用场景**: 
- 手动标记任务完成

---

#### `goal.clear` - 清除目标

**功能**: 删除目标。

**请求参数**:
```json
{
  "goalId": "goal-uuid"
}
```

**返回值**: `{}`

**使用场景**: 
- 取消任务
- 清理过期目标

---

### 2.8 Settings 设置

#### `settings.describe` - 描述设置

**功能**: 获取所有设置命名空间的当前值和 schema。

**请求参数**: `{}`

**返回值**:
```json
{
  "namespaces": [
    {
      "ns": "llm-pi-ai",
      "value": { ... },
      "schema": { ... },
      "revision": 123,
      "writable": true
    }
  ],
  "writable": true
}
```

**使用场景**: 
- 设置页面加载
- 读取配置

---

#### `settings.openDocument` - 打开设置文档

**功能**: 用编辑器打开指定命名空间的配置文件。

**请求参数**:
```json
{
  "ns": "llm-pi-ai"
}
```

**返回值**: `{}`

**使用场景**: 
- 高级配置编辑

---

#### `settings.update` - 更新设置

**功能**: 使用 JSON Patch 更新设置。

**请求参数**:
```json
{
  "ns": "agent-presets",
  "patch": {
    "default": "minimal"
  }
}
```

**返回值**: `{}`

**使用场景**: 
- 修改 Agent 预设
- 修改权限设置
- 修改主题设置

---

#### `settings.replace` - 替换设置

**功能**: 完全替换指定命名空间的值。

**请求参数**:
```json
{
  "ns": "llm-pi-ai",
  "value": { ... }
}
```

**返回值**: `{}`

**使用场景**: 
- 导入配置
- 批量更新

---

#### `settings.mutate` - 变更设置

**功能**: 使用 JSON Patch 操作数组变更设置（支持 set/unset 操作）。

**请求参数**:
```json
{
  "ns": "llm-pi-ai",
  "ops": [
    {
      "op": "set",
      "path": ["providers", "xiaomi"],
      "value": {
        "apiKeyEnv": "XIAOMI_API_KEY",
        "baseURL": "https://...",
        "models": [...]
      }
    }
  ],
  "expectedRevision": 123
}
```

操作类型:
- `set`: 设置值
- `unset`: 删除值

**返回值**:
```json
{
  "revision": 124
}
```

**使用场景**: 
- 保存提供方配置
- 添加/删除提供方
- 更新模型列表

---

### 2.9 Credentials 凭据

#### `credentials.describe` - 描述凭据

**功能**: 查询指定凭据引用的状态（是否已配置、是否可写等）。

**请求参数**:
```json
{
  "refs": ["DEEPSEEK_API_KEY", "XIAOMI_API_KEY"]
}
```

**返回值**:
```json
{
  "credentials": {
    "DEEPSEEK_API_KEY": {
      "configured": true,
      "writable": true,
      "value": "sk-***"
    },
    "XIAOMI_API_KEY": {
      "configured": false,
      "writable": true
    }
  }
}
```

**使用场景**: 
- 检查 API Key 是否配置
- 显示凭据状态（红/绿点）

---

#### `credentials.set` - 设置凭据

**功能**: 设置 API Key 或其他凭据。

**请求参数**:
```json
{
  "ref": "DEEPSEEK_API_KEY",
  "value": "sk-your-api-key-here"
}
```

**返回值**: `{}`

**使用场景**: 
- 保存 API Key
- 更新凭据

---

#### `credentials.unset` - 删除凭据

**功能**: 删除指定凭据。

**请求参数**:
```json
{
  "ref": "DEEPSEEK_API_KEY"
}
```

**返回值**: `{}`

**使用场景**: 
- 删除 API Key
- 清除凭据

---

### 2.10 LLM 大语言模型

#### `llm.providers` - 列出提供方

**功能**: 获取所有 LLM 提供方的列表（包括内置和自定义）。

**请求参数**: `{}`

**返回值**:
```json
{
  "providers": [
    {
      "provider": "deepseek-official",
      "displayName": "DeepSeek",
      "settingsNs": "llm-deepseek",
      "settingsPath": [],
      "active": true
    },
    {
      "provider": "xiaomi",
      "displayName": "xiaomi",
      "settingsNs": "llm-pi-ai",
      "settingsPath": ["providers", "xiaomi"],
      "active": true,
      "declared": false
    }
  ]
}
```

**使用场景**: 
- 模型配置页面的提供方列表
- 判断提供方是否激活

---

#### `llm.models` - 列出模型

**功能**: 获取运行时可用的模型目录（按提供方分组）。

**请求参数**: `{}`

**返回值**:
```json
{
  "groups": [
    {
      "id": "deepseek-official",
      "name": "DeepSeek",
      "models": [
        {
          "id": "deepseek-v4-flash",
          "name": "DeepSeek-V4-Flash",
          "reasoning": {
            "efforts": [...],
            "defaultEffort": "high"
          }
        }
      ]
    }
  ]
}
```

**使用场景**: 
- 输入卡片的模型选择器
- 模型切换

---

#### `llm.discoverModels` - 发现模型

**功能**: 询问提供方端点有哪些可用模型（用于添加新提供方时获取模型列表）。

**请求参数**:
```json
{
  "settingsNs": "llm-pi-ai",
  "provider": "xiaomi",
  "baseURL": "https://api.example.com/v1",
  "apiKey": "optional-api-key"
}
```

**返回值**:
```json
{
  "models": [
    {
      "id": "model-id",
      "name": "Model Name",
      "contextWindow": 131072,
      "maxTokens": 8192
    }
  ]
}
```

**使用场景**: 
- 添加新提供方时获取模型列表
- 验证 API Key 有效性
- 发现端点支持的模型

---

## 三、WebSocket 接口

### 3.1 MUX 事件通道

**路径**: `/api/events.mux`
**协议**: WebSocket
**用途**: 会话级别的实时事件流（消息、工具调用、投影更新等）

#### 连接建立

```
GET /api/events.mux HTTP/1.1
Upgrade: websocket
Connection: Upgrade
```

#### 服务器推送事件格式

```json
{
  "type": "server-event",
  "rpcId": "event-uuid",
  "payload": {
    "type": "event-type",
    "sessionId": "session-uuid",
    "event": { ... }
  }
}
```

#### 事件类型

| 事件类型 | 说明 | 数据结构 |
|----------|------|----------|
| `session/event` | 会话事件包装 | `{ type, sessionId, event }` |
| `agent/inbox/spliced` | 代理消息插入 | `{ inserted: Message[] }` |
| `assistant/chunk` | 助手输出增量 | `{ delta: "text", reasoning?: "text" }` |
| `assistant/message` | 助手消息完成 | `{ message: Message }` |
| `tool/result` | 工具执行结果 | `{ toolCallId, content, isError }` |
| `user/message` | 用户消息确认 | `{ message: Message }` |
| `turn/start` | 回合开始 | `{ turnId }` |
| `turn/end` | 回合结束 | `{ turnId }` |
| `step/end` | 步骤结束 | `{ stepId }` |
| `session/projection` | 投影更新 | `{ projection }` |
| `approval/requested` | 审批请求 | `{ rpcId, approvalId, toolName, reason }` |

#### 使用场景

- 实时消息流（打字机效果）
- 工具调用状态
- 投影统计更新
- 审批弹窗

---

### 3.2 HOST 事件通道

**路径**: `/api/events.host`
**协议**: WebSocket
**用途**: 主机级别的全局事件（连接状态、配置更新等）

#### 事件类型

| 事件类型 | 说明 | 数据结构 |
|----------|------|----------|
| `llm/adapters-updated` | LLM 适配器更新 | `{}` |
| `settings/document-updated` | 设置文档更新 | `{ ns: "namespace" }` |

#### 使用场景

- 模型列表刷新
- 配置变更通知
- 连接状态监控

---

## 四、数据交换格式

### 4.1 消息 (Message) 结构

```typescript
interface Message {
  id: string;                    // 消息唯一标识
  role: "user" | "assistant";    // 角色
  content: ContentBlock[];       // 内容块数组
  source: {
    kind: "user" | "model" | "tool";
    provider?: string;           // 模型提供方
    model?: string;              // 模型名称
    callId?: string;             // 工具调用 ID
  };
  timestamp?: string;            // 时间戳
}
```

### 4.2 内容块 (ContentBlock) 类型

| 类型 | 说明 | 字段 |
|------|------|------|
| `text` | 文本内容 | `{ type: "text", text: string }` |
| `reasoning` | 推理过程 | `{ type: "reasoning", text: string }` |
| `context-injection` | 上下文注入 | `{ type: "context-injection", text: string }` |
| `tool-call` | 工具调用 | `{ type: "tool-call", toolCallId, name, arguments }` |
| `tool-result` | 工具结果 | `{ type: "tool-result", toolCallId, content, isError }` |

### 4.3 投影 (Projection) 结构

```typescript
interface Projections {
  permissions?: {
    options: Array<{ value: string, name: string }>;
    currentValue: string;
  };
  sessionStats?: {
    turns: number;
    steps: number;
    llmMs: number;
    toolMs: number;
    decodeTokens: number;
  };
  contextPressure?: {
    pressureTokens: number;
    contextWindow: number;
  };
}
```

---

## 五、处理逻辑流程

### 5.1 HTTP RPC 处理流程

```
┌─────────────────────────────────────────────────────────────┐
│                    HTTP 请求处理流程                         │
├─────────────────────────────────────────────────────────────┤
│  1. 客户端发送 POST /api/<method>                           │
│  2. WebServer.match(pathname) 匹配路由                      │
│  3. client-connection 插件处理请求                          │
│  4. 解析 JSON-RPC 信封                                      │
│  5. 验证 trustedHosts (本地回环默认信任)                    │
│  6. 路由到对应方法处理器                                    │
│  7. 执行业务逻辑                                           │
│  8. 返回 JSON-RPC 响应                                     │
└─────────────────────────────────────────────────────────────┘
```

### 5.2 WebSocket 处理流程

```
┌─────────────────────────────────────────────────────────────┐
│                    WebSocket 处理流程                        │
├─────────────────────────────────────────────────────────────┤
│  1. 客户端发送 GET /api/events.mux (Upgrade: websocket)     │
│  2. WebServer 处理 WebSocket 升级                           │
│  3. 建立双向通信通道                                        │
│  4. 服务器推送事件帧                                        │
│  5. 客户端接收并处理事件                                    │
│  6. 支持重连 (指数退避)                                     │
└─────────────────────────────────────────────────────────────┘
```

### 5.3 会话消息流程

```
用户输入 → session.prompt
    ↓
后端处理 → turn/start
    ↓
AI 生成 → assistant/chelta (流式)
    ↓
工具调用 → tool-call → tool/result
    ↓
生成完成 → assistant/message → turn/end
    ↓
投影更新 → session/projection
```

---

## 六、特权方法

某些方法只允许受信任的主机（本地回环 127.0.0.1）调用:

```javascript
const PRIVILEGED_METHODS = new Set([
  "host.pickDirectory",
  "host.listDirectory", 
  "host.createDirectory",
  "host.openPath"
]);
```

外部请求调用这些方法返回 403 Forbidden。

---

## 七、配置项

### 7.1 WebServer 配置

```yaml
webServer:
  host: "127.0.0.1"    # 绑定地址
  port: 3080           # 监听端口
```

### 7.2 安全配置

```yaml
clientConnection:
  trustedHosts: []           # 受信任的主机列表
  maxRequestBodyBytes: 314572800  # 最大请求体 (300MB)
```

### 7.3 默认行为

- **本地回环 (127.0.0.1)**: 默认信任，可调用所有方法
- **外部请求**: 只能调用非特权方法
- **WebSocket**: 需要有效的会话上下文

---

## 附录: 完整方法列表

```
credentials.describe
credentials.set
credentials.unset
goal.clear
goal.complete
goal.create
goal.edit
goal.pause
goal.resume
host.createDirectory
host.describe
host.listDirectory
host.openPath
host.pickDirectory
llm.discoverModels
llm.models
llm.providers
session.attachment
session.cancel
session.create
session.fork
session.history
session.list
session.models
session.prompt
session.rename
session.search
session.selectModel
session.updateQueue
settings.describe
settings.mutate
settings.openDocument
settings.replace
settings.update
skill.list
subagent.history
subagent.interrupt
subagent.list
subagent.prompt
workspace.archiveSession
workspace.create
workspace.delete
workspace.insertBefore
workspace.insertSessionBefore
workspace.list
workspace.rename
```

共计 **45 个 RPC 方法** + **2 个 WebSocket 通道**。
