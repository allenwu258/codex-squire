# 管理 API 与网关接口

## 鉴权

管理请求使用 `Authorization: Bearer <admin.token>`；网关请求使用 `Authorization: Bearer <gateway.token>`。两种令牌不可互换。`GET /api/health` 无需令牌，仅返回状态与版本。

非白名单 Origin 返回 403。无 Origin 的本机 CLI 请求仍需令牌。响应统一设置 `Cache-Control: no-store`。

管理错误结构：

```json
{"error":{"code":"CONFLICT","message":"Name already exists or resource is referenced by a scheme"}}
```

常用状态：400 字段不合法，401 令牌错误，403 来源不允许，404 资源不存在，409 状态/唯一性/引用冲突，413 请求过大，415 媒体类型错误，502 上游连接或重定向被拒绝，503 无生效方案/无可用来源。普通上游响应在透传路径保留 HTTP 状态和正文；3xx 会转换为本地 502，不透传重定向；用于换源的 429 也不会直接输出给下游。

白名单 Origin 的 OPTIONS 预检无需令牌，返回 204。健康检查虽然不要求令牌，仍受 Origin 限制。管理 API 的成功 JSON 响应没有额外 data 包装；删除成功为 204 空响应，调用方不得继续解析 JSON。

## Provider

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/api/providers` | 列出来源，不返回 API Key 或密文 |
| POST | `/api/providers` | 创建，返回 201 与实体 |
| PUT | `/api/providers/{id}` | 完整更新配置；必须携带当前版本的 `If-Match: "<version>"` |
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

`name` 非空、最长 80；`baseUrl` 非空、最长 2048；`apiKey` 最长 8192，禁止换行。name 经 trim 后唯一。日限额允许 0，表示不限。PUT 是完整配置更新，不是 PATCH；除 apiKey 的保留语义外，应发送全部配置字段。不要依赖省略基本类型字段时的反序列化零值，例如省略 enabled 可能导致来源禁用。

响应实体字段还包括 `version`。更新成功返回对应的 `ETag`；缺失 `If-Match` 返回 428，版本过期返回 412。列表返回实体数组；不返回 apiKey/encryptedKey。时间使用 UTC ISO-8601 字符串。

Base URL 不包含最终 `/responses`；已有路径则在其后追加 `/responses`，根地址则追加 `/v1/responses`。只允许 HTTP(S)，远程来源必须 HTTPS，明文 HTTP 仅允许 localhost/127.0.0.1/IPv6 loopback。禁止 URL 用户信息、查询参数和 fragment。

## Scheme

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/api/schemes` | 返回方案与有序成员列表 |
| POST | `/api/schemes` | 创建非生效方案，返回 201 |
| PUT | `/api/schemes/{id}` | 原子更新名称、策略与完整成员列表；必须携带当前版本的 `If-Match` |
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

名称非空、最长 80，trim 后唯一。响应字段为 `id`、`name`、`strategy`、`active`、`version`、`members`；每个成员包含 `providerId`、`weight`。保存和激活成功返回实体，普通更新为 200 并返回 `ETag`。生效方案不能更新为空或直接删除，分别返回 409；成员引用不存在的 Provider 返回冲突，整次更新回滚。

## 用量和连接

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| GET | `/api/statistics` | UTC 当天聚合及各来源用量 |
| GET | `/api/attempts?limit=30&offset=0` | 倒序尝试记录；limit 1..200，默认 50；offset 0..1000000，默认 0 |
| GET | `/api/connection` | 本地网关 URL、网关令牌、数据目录、限额时区 |

`statistics` 中 inputTokens / outputTokens / cachedTokens 只包含上游已报告值。`chargedTokens` 包含已报告值及保留的预留占额，包括进行中尝试。`uncertainRequests` 只统计 `status != IN_FLIGHT` 且 `usageState = RESERVED` 的记录，不包括无 usage 但按拒绝处理的 NOT_APPLICABLE。`inFlight` 按所有日期仍为 `IN_FLIGHT` 的尝试计算，与准入口径一致。

统计顶层字段：`day`（UTC yyyy-MM-dd）、`requests`、`attempts`、`successes`、`failures`、`inFlight`、`inputTokens`、`outputTokens`、`cachedTokens`、`chargedTokens`、`uncertainRequests`、`providers`。顶层 `requests` 计逻辑请求；`attempts` 计准入的 Provider 尝试，均不计未准入的请求。`successes` 计 SUCCEEDED，`failures` 计除 IN_FLIGHT/SUCCEEDED 外的状态，包括取消和中断。providers 为当日有记录或仍有跨日活动尝试的来源聚合数组，每项的 `requests` 仍是 attempt 数，`inFlight` 跨所有日期计算。

`attempts` 中 requestId 用于关联故障转移前后的多次尝试，id 标识单次尝试。`usageState` 为 `REPORTED`、`RESERVED` 或 `NOT_APPLICABLE`。HTTP 状态与最终状态独立：流式响应可能 HTTP 200 但最终 `INCOMPLETE` 或 `CANCELLED`。

attempts 返回数组，没有总条数包装。按 startedAt、id 倒序排列；字段为 `id`、`requestId`、`providerId`、`providerName`、`model`、`startedAt`、`endedAt`、`status`、`httpStatus`、`inputTokens`、`outputTokens`、`cachedTokens`、`chargedTokens`、`usageState`、`latencyMs`。endedAt 在途时为 null，httpStatus 尚未收到上游状态时为 null；缺失已报告用量时 token 数字为 0，必须结合 usageState 解读。latencyMs 在结算时写入，单位毫秒。

状态包括 IN_FLIGHT、SUCCEEDED、FAILED、INCOMPLETE、RATE_LIMITED、UPSTREAM_ERROR、TRANSPORT_ERROR、CANCELLED、INTERRUPTED。`upstreamOutcome` 与 `deliveryOutcome` 独立保存，例如上游已完成但客户端取消可同时表示 `COMPLETED` 与 `CLIENT_CANCELLED`。3xx 使用 `UPSTREAM_REDIRECT` 分类并保留在 attempt 记录中。

connection 返回 `baseUrl`（含 /v1）、`gatewayToken`、`dataDirectory`、`quotaTimeZone`（UTC）。它包含网关凭据，必须使用管理令牌读取，不应写入公开日志。`GET /api/health` 返回 `{"status":"UP","version":"0.1.0"}`，不证明上游来源可用。

## Responses 网关

```http
POST /v1/responses
Authorization: Bearer <gateway.token>
Content-Type: application/json

{"model":"<upstream-model-id>","input":"Hello","stream":true,"store":false}
```

请求体在大小限制内原样转发，不改模型、工具、reasoning 或输出格式。必须存在文本 model 和 input 字段。`background=true` 或非 null 的 `previous_response_id` 返回 400；本版要求会话内容直接放在 input 中。

model 必须非空且最长 200；input 目前只检查字段存在，内容兼容性由上游决定。请求需 application/json，最大 8 MiB。响应中的文本、工具调用等原始字节被转发；这不表示本版实现了所有 Responses 子接口。仅明确的 429 且存在后续候选时尝试换源；3xx 不跟随，其他 HTTP 错误和网络异常不自动重放。

附加响应头：

- `x-squire-request-id`：本地网关关联 ID。
- `x-squire-provider-id`：本次真正输出响应的来源 ID。
- `x-request-id`、`retry-after`、`openai-processing-ms`、`x-codex-turn-state`：上游存在时透传。

入站 Authorization 被来源 API Key 替换。有限透传 OpenAI-Beta、originator、version、session_id、conversation_id、x-codex-turn-state、x-codex-turn-metadata；不会透传 Cookie 或全部任意头部。

不提供 `/v1/chat/completions`、模型目录、远程响应读取、删除、压缩或 WebSocket 端点。未知 `/api/*` 路径先通过管理鉴权，其他未知路径先通过网关鉴权，再由路由返回 404；允许的 OPTIONS 预检除外。

## 本地错误码

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | INVALID_REQUEST / INVALID_JSON / INVALID_RESPONSES_REQUEST | 字段、JSON 或 Responses 基础结构不合法 |
| 400 | INVALID_BASE_URL / HTTPS_REQUIRED / INVALID_API_KEY / API_KEY_REQUIRED | 来源地址或凭据输入不合法 |
| 400 | DUPLICATE_MEMBER / INVALID_PAGE | 重复成员或分页越界 |
| 400 | UNSUPPORTED_RESPONSE_MODE | background/previous_response_id 不支持 |
| 401 / 403 | UNAUTHORIZED / ORIGIN_DENIED | 令牌错误或浏览器来源不允许 |
| 404 | PROVIDER_NOT_FOUND / SCHEME_NOT_FOUND | 管理资源不存在 |
| 409 | CONFLICT | 名称唯一性或引用约束冲突 |
| 412 / 428 | CONFIG_VERSION_CONFLICT / CONFIG_VERSION_REQUIRED | 配置版本过期或缺少 `If-Match` |
| 409 | EMPTY_ACTIVE_SCHEME / EMPTY_SCHEME / ACTIVE_SCHEME | 生效方案不能为空、空方案不能激活、生效方案不能删除 |
| 413 | REQUEST_TOO_LARGE | 请求体超过上限 |
| 415 等 | HTTP_415 等 | Spring HTTP 状态异常转换 |
| 500 | CREDENTIAL_DECRYPTION_FAILED / STORAGE_ERROR / DATABASE_SETTLEMENT_ERROR | 密钥、存储或结算失败 |
| 502 | UPSTREAM_REDIRECT / UPSTREAM_TIMEOUT / UPSTREAM_CONNECTION_ERROR | 重定向、超时或连接失败 |
| 503 | NO_ACTIVE_SCHEME / NO_AVAILABLE_PROVIDER | 没有生效方案或候选全部不可用 |
上游透传错误正文不保证符合本地 error 结构；SSE 已提交后的失败也不能再转换成普通 JSON 错误响应。Provider 探测使用真实响应 `Content-Type` 判断 JSON/SSE，单机最多并发 2 个探测，响应体解析上限 1 MiB。
