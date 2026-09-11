# 管理 API 与网关接口

## 鉴权

管理请求使用 `Authorization: Bearer <admin.token>`；网关请求使用 `Authorization: Bearer <gateway.token>`。两种令牌不可互换。`GET /api/health` 无需令牌，仅返回状态与版本。

非白名单 Origin 返回 403。无 Origin 的本机 CLI 请求仍需令牌。响应统一设置 `Cache-Control: no-store`。

管理错误结构：

```json
{"error":{"code":"CONFLICT","message":"Name already exists or resource is referenced by a scheme"}}
```

常用状态：400 字段不合法，401 令牌错误，403 来源不允许，404 资源不存在，409 状态/唯一性/引用冲突，503 无可用来源。上游非重试响应保留其 HTTP 状态和正文。

## Provider

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/api/providers` | 列出来源，不返回 API Key 或密文 |
| POST | `/api/providers` | 创建，返回 201 与实体 |
| PUT | `/api/providers/{id}` | 完整更新配置，返回实体 |
| DELETE | `/api/providers/{id}` | 删除，返回 204；方案引用会返回 409 |

创建/更新示例：

```json
{
  "name": "Primary",
  "baseUrl": "https://api.example.com/v1",
  "apiKey": "<provider-key>",
  "enabled": true,
  "dailyRequestLimit": 1000,
  "dailyTokenLimit": 1000000,
  "maxConcurrency": 4,
  "tokenReservation": 8192
}
```

更新时 `apiKey` 为空字符串、null 或省略表示保留。创建必须传非空值。ID、创建时间由后端生成。请求上限最大 10^9、Token 上限最大 10^12、并发 1..128、预留 1..10^7。

Base URL 不包含最终 `/responses`；已有路径则在其后追加 `/responses`，根地址则追加 `/v1/responses`。

## Scheme

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/api/schemes` | 返回方案与有序成员列表 |
| POST | `/api/schemes` | 创建非生效方案，返回 201 |
| PUT | `/api/schemes/{id}` | 原子更新名称、策略与完整成员列表 |
| POST | `/api/schemes/{id}/activate` | 原子切换生效方案 |
| DELETE | `/api/schemes/{id}` | 删除非生效方案，返回 204 |

```json
{
  "name": "Daily",
  "strategy": "BALANCED",
  "members": [
    {"providerId": "<id-a>", "weight": 2},
    {"providerId": "<id-b>", "weight": 1}
  ]
}
```

策略为 `ORDERED` 或 `BALANCED`。数组顺序就是优先级；权重 1..100，单方案最多 100 个成员，同一 Provider 不能重复出现。创建不隐式生效；空方案可保存草稿但不可激活。

## 用量和连接

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| GET | `/api/statistics` | UTC 当天聚合及各来源用量 |
| GET | `/api/attempts?limit=30&offset=0` | 倒序尝试记录；limit 1..200 |
| GET | `/api/connection` | 本地网关 URL、网关令牌、数据目录、限额时区 |

`statistics` 中 inputTokens / outputTokens / cachedTokens 只包含上游已报告值。`chargedTokens` 包含实际值及未知请求的预留占额。`uncertainRequests` 是已结束但缺失 usage 的尝试数，`inFlight` 是今日启动并仍在处理的尝试数。

`attempts` 中 requestId 用于关联故障转移前后的多次尝试，id 标识单次尝试。`usageState` 为 `REPORTED`、`RESERVED` 或 `NOT_APPLICABLE`。HTTP 状态与最终状态独立：流式响应可能 HTTP 200 但最终 `INCOMPLETE` 或 `CANCELLED`。

## Responses 网关

```http
POST /v1/responses
Authorization: Bearer <gateway.token>
Content-Type: application/json

{"model":"<upstream-model-id>","input":"Hello","stream":true,"store":false}
```

请求体在大小限制内原样转发，不改模型、工具、reasoning 或输出格式。必须存在文本 model 和 input 字段。`background=true` 或非 null 的 `previous_response_id` 返回 400；本版要求会话内容直接放在 input 中。

附加响应头：

- `x-squire-request-id`：本地网关关联 ID。
- `x-squire-provider-id`：本次真正输出响应的来源 ID。
- `x-request-id`、`retry-after`、`openai-processing-ms`、`x-codex-turn-state`：上游存在时透传。

入站 Authorization 被来源 API Key 替换。有限透传 OpenAI-Beta、originator、version、session_id、conversation_id、x-codex-turn-state、x-codex-turn-metadata；不会透传 Cookie 或全部任意头部。

不提供 `/v1/chat/completions`、模型目录、远程响应读取、删除、压缩或 WebSocket 端点。未知路径必须先通过网关鉴权，之后返回 404。
