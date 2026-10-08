# ADR 0001：限量活动订单消息通道

- 状态：Accepted（保留 Redis Stream；实现和上线验收状态见下文）
- 日期：2026-10-08
- 范围：Phase 3B 订单事件消费与失败恢复

## 背景与决策

CampusHub 是单体应用，MySQL 是订单事实来源，现有单机 Redis 同时承载会话、缓存、活动预扣和订单 Stream。Phase 3A 已将 `userId`、`voucherId`、`id` 写入 `stream.orders`；`ACCEPTED` 只表示 Redis 已受理，不表示 MySQL 订单已创建。当前没有订单吞吐、积压、恢复时间或延迟的实测数据证明 Stream 已达到容量边界。

因此保留 Redis Stream `stream.orders`，先完成当前消费、pending 恢复和失败归档方案；目前不引入 RabbitMQ 或 RocketMQ。专业 MQ 不会自动解决 Redis 与 MySQL 的双写一致性、数据库幂等或事务提交结果不确定问题。

## 当前实现行为

- 每个实例使用 `order-` 前缀加随机 UUID 的消费者名，消费组为 `g1`。
- 每轮先扫描 pending，再读取新消息。pending 用 `XPENDING` 分页，每页 32 条，并用 `XCLAIM` 接管空闲至少达到阈值的消息；新消息每次读 1 条，阻塞 2 秒。分页游标避免热消息长期挡住后续记录。32 条、1 条和 2 秒目前是代码常量。
- 接管空闲阈值默认 60,000 ms，可由 `ORDER_CLAIM_IDLE_MS` 配置，最小 1,000 ms。重试由这个固定阈值触发，不是指数退避；当前没有逐条退避计划或延迟队列。
- 消息尝试次数在调用数据库处理前持久写入 Redis。进程在数据库调用中或之后崩溃，该次也计数。`ORDER_MAX_ATTEMPTS` 默认 5，允许范围 1..100。只保存总尝试次数，不分别保存首次失败和重试数，也不逐次保存失败历史；达到上限后才写最终失败记录。
- 成功路径的 ACK 异常不作为数据库处理异常归档；若同一事件随后重新投递，则依赖数据库幂等核验。ACK、尝试计数和失败归档前均检查该消息仍归当前消费者所有，旧 owner 不得 ACK 或归档。
- 失败分类只保存安全分类：业务异常的原因码或异常类名；不保存异常文本。DLQ 保存 `payload`（原始 Stream fields 的 JSON）、`sourceId`、`group`、`consumer`、`attempts`、`errorClass`、`failedAt`（Unix 秒）和 `reservation=RETAINED`。索引为 `sourceId -> deadID`。DLQ 记录最终失败，不保存每次失败历史。
- 预扣库存标记为保留，不自动返还。数据库提交结果不确定时，不能把异常直接解释为订单未落库。

## Redrive 与处置约束

已交付 operator-only `redrive-order.lua` 与人工核对 runbook，没有公开 HTTP 管理接口。操作者先核对数据库，再以原订单 ID 显式重放；`stream.orders.redrives` Hash 保存 `deadID -> newSourceID`，以及 `deadID:operator`、`deadID:reason`、`deadID:at`。重复调用同一 deadID 返回同一 newSourceID。操作 label 不等于受认证身份；权限和外部审计仍依赖受控 Redis ACL，尚未完成上线验收。

同 ID 重放若发现数据库已有匹配订单，应按幂等成功确认，不再扣一次数据库库存；若发现冲突或提交结果仍不确定，先停止重放并继续核对。redrive 不返还 Redis 预扣库存。首次确认进入 DLQ 后，在处置核验完成前，禁止清理该 DLQ 记录及其 `sourceId -> deadID` 索引；当前没有已验收的保留期、清理程序或容量治理能力。

## 方案成本比较

| 方案 | 本项目需承担的成本 | 适用收益与限制 | 决定 |
| --- | --- | --- | --- |
| 保留 Redis Stream | 继续维护当前 Redis 消费、pending 接管、计数、最终失败快照及 redrive；上线前还需完成 Redis 持久化/备份、权限、容量、保留和告警验收。 | 复用现有 Redis、事件格式和应用依赖，不增加新的服务、发布链路和日常值守面。Redis 与会话、缓存及活动预扣共享单机故障域，可靠性受 Redis 配置和运维恢复能力约束。 | 当前低规模且无实测瓶颈，保留。 |
| RabbitMQ | 新增 Broker 部署与升级、Spring AMQP 配置、exchange/queue/binding、持久化与确认、DLX/重试拓扑、权限、监控、备份恢复和演练；改造生产消费与部署，并处理 Redis 受理到 MQ 发布的双写窗口。 | 独立消息代理及路由、队列治理能力更丰富；仍需自行验证持久化、复制、切换和跨系统一致性。 | 目前没有证据证明收益覆盖新增运维面，暂不迁移。 |
| RocketMQ | 新增 Broker/NameServer 等组件与升级管理、客户端、topic/group/权限、存储复制、监控、备份恢复和运维手册；改造生产消费，并处理 Redis 到 MQ 的双写窗口。 | 可支持更复杂或更大规模的消息场景；当前单体与未测负载没有证明需要这套独立平台。 | 暂不迁移，出现明确需求和实测依据后再评估。 |

## 重新评估条件

在预先定义的目标负载和服务目标下，出现以下任一情况时重新比较 Redis Stream 与专业 MQ；触发评估不等于自动迁移：

1. 实测积压持续增长、最老消息年龄超过订单处理目标，或消费者无法在恢复目标内清空积压。
2. Redis 的内存、CPU、持久化或故障恢复表现与缓存、会话、活动流量竞争，导致既定服务目标或 RPO/RTO 无法满足。
3. 业务明确需要独立于 Redis 的消息故障域、独立扩缩容、跨服务路由或更长的保留/重放能力，并由需求和演练证明现方案不足。

评估需附原始负载或故障演练数据、资源与积压指标，以及新方案的部署、双写/切换、回滚和运维责任；不能仅凭“高并发”推断迁移必要。

## 实现状态与上线验收边界

**代码已有的消费行为：** UUID 消费者、固定参数的 pending 分页与新消息读取、固定空闲阈值 `XCLAIM`、调用数据库前持久化总尝试次数、最大次数范围校验、owner 检查、最终失败 DLQ 字段和 sourceId 索引。当前代码路径仍需通过对应目标环境验证；本 ADR 不据此宣称生产可靠性已验证。

**代码已有的恢复入口：** Operator-only Lua、同一 deadID 幂等重投、原订单 ID 保真、操作 label/reason/time 留痕与人工核对流程。没有实现 HTTP 管理权限平台；Redis ACL 和受认证身份的外部操作审计属于上线前治理。重放依赖数据库已有订单核验实现幂等确认，不额外扣库存。

**上线前仍待验收：** Redis 持久化、备份恢复、ACL、Stream/DLQ 保留与容量策略、告警及处置核验前禁止清理的运维控制；Redis 6 实例真机验证；真实 MySQL 验证；HTTP 到 MySQL 的订单端到端与消费者恢复验证；实际压测。当前没有实测 QPS、延迟、容量或生产恢复结论，也没有已交付的 DLQ 容量配置和告警能力。

订单状态查询、超时、取消或核销的 HTTP 闭环属于 Phase 3C，当前未实现。测试或静态代码检查不能替代 MySQL、端到端、Redis 6 真机、运维恢复和性能验收。

## 参考

- `docs/refactor/02-migration-plan.md`：Phase 3B/3C 阶段范围。
- `docs/refactor/01-target-architecture.md`：MySQL 事实来源与 Redis Stream 初始选型。
- `docs/refactor/10-phase-3a-flash-sale-admission.md`：受理语义、事件字段与验证边界。
- `docs/refactor/11-phase-3b-reliable-order-consumption.md`：实际验证、失败治理、人工核对与 redrive runbook。
- `docs/learning/flash-sale-admission.md`：Redis 受理与 MySQL 落单的一致性边界。
