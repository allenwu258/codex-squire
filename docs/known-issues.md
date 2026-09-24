# 已知问题与开发顺序

更新日期：2026-09-11。以下描述当前第一版实现；文档和启动配置更新不代表这些业务问题已修复。

## 优先解决的正确性问题

| 编号 | 当前行为与影响 | 计划及验收方向 |
| --- | --- | --- |
| K01 | 加权算法在全部成员上分配票号，再跳过不可用来源。A/B/C 权重 100/1/1、A 不可用时，后续 B/C 可能分配为 101:1 | 按有效候选决策，同时保留原子准入；验证禁用、额度不足、恢复和方案切换后的公平性 |
| K02 | 收到 completed 后再取消，usage 可以保留为 REPORTED，但 attempt 被记为 CANCELLED；错误清理也会覆盖已确定分类 | 分离上游结果和交付结果；覆盖终态后取消、写入中取消、未完成流等时序 |
| K03 | 3xx 返回 502/UPSTREAM_REDIRECT，但日志状态会被异常清理覆盖为 TRANSPORT_ERROR；部分数据库/解密异常可能被映射为 UPSTREAM_FAILURE | 保留已确定业务分类，区分存储、配置、协议与网络错误 |
| K04 | 已建立版本化迁移器、`schema_migrations` 和 checksum，并能接管现有 V1；尚未有 V2 以上的真实增量迁移历史 | 新增持久化字段前继续补版本迁移、升级失败恢复和备份恢复测试 |
| K05 | 已有加密凭据但 `master.key` 缺失时现在会拒绝启动；密文格式版本、密钥轮换和系统凭据库尚未实现 | 增加密文版本/keyId、轮换流程，并在桌面阶段接入系统凭据库 |

证据入口：[RoutingService](../backend/src/main/java/dev/squire/gateway/RoutingService.java)、[GatewayService](../backend/src/main/java/dev/squire/gateway/GatewayService.java)、[Database](../backend/src/main/java/dev/squire/persistence/Database.java)、[LocalSecrets](../backend/src/main/java/dev/squire/security/LocalSecrets.java)。已有加权测试仅覆盖所有来源可用的 2:1 场景；已有取消集成测试没有覆盖 completed 后再取消，不能用测试通过表示 K01/K02 已消失。

## 后续能力与边界

- 额度目前只有 UTC 当日请求/token 上限，尚无多窗口规则；预留预算不能保证供应商硬费用上限。
- 统计中的 inFlight 只覆盖今日启动的尝试，实际并发准入覆盖所有日期；跨午夜时两个值可能不同。
- 触发换源的 429 响应体被释放，未解析其中可能存在的 usage；没有基于 Retry-After 的冷却，也没有整个网关请求共享的 deadline。
- 缺少完整逻辑请求/attempt 链、首包耗时、结构化跳过原因、长期归档和供应商账单对账。
- 配置未使用乐观锁；前端轮询没有完整的过期响应丢弃机制。多窗口编辑和慢响应可能造成覆盖。
- 前端管理调用使用 ApiClient，但连通测试仍直接 fetch；Tauri 阶段必须统一 transport、进程归属、Origin 和凭据获取。
- 不支持远程状态续接、compact、WebSocket、OAuth、会话管理和提示词管理；现有元数据不能视作这些能力已经实现。

## 开发顺序

1. 修复 K01/K02/K03，补对应路由和生命周期回归测试；在新增字段前继续扩展 K04 的版本迁移与升级测试。
2. 增加多窗口限额、请求诊断、单来源探测、可解释的冷却和一致的统计口径。
3. 改善配置并发编辑与前端请求管理，建立统一后端连接和进程发现机制。
4. 在明确来源归属后加入会话/提示词领域，再封装 Tauri；桌面生命周期和密钥库单独验收。

本文件是当前问题和优先级入口。修复后更新行为说明、回归测试入口和完成提交，不将尚未实现的设计写成现有能力。
