# Phase 3A 限量活动受理规则与验证记录

## 范围和业务语义

本阶段只处理活动受理：创建活动后发布 Redis 规则，抢购时核验资格、状态、时间和库存，通过 Lua 将受理事件写入既有 `stream.orders`。订单消费者和数据库落单流程仍沿用现状，可靠消费与订单状态闭环分别属于 Phase 3B、3C。

`POST /voucher-order/seckill/{id}` 保留路径及 `Result` 包装，成功时 `data` 仍为数字订单 ID，并新增 `acceptanceStatus: "ACCEPTED"` 和 `replayed: false|true`。`ACCEPTED` 仅说明受理事件已进入 Redis Stream，不表示 MySQL 订单已落库、已付款或最终成功。同一有效账号重试同一活动，在去重记录有效期内返回原 ID 并标记 `replayed: true`，不会再扣库存或写事件。Redis 调用超时或结果无法确认时返回 `ACTIVITY_UNAVAILABLE`（503）；请求可能已经受理，客户端应使用同一账号重试。

拒绝码：未开始 `ACTIVITY_NOT_STARTED`、已结束 `ACTIVITY_ENDED`、未上架 `ACTIVITY_INACTIVE`、售罄 `SOLD_OUT`、历史参与但找不到原 ID `ALREADY_PARTICIPATED` 均为 409；资格不足为 `AUTHORIZATION_FAILED`（403）；规则缺失、损坏、Redis 异常或未知脚本结果为 `ACTIVITY_UNAVAILABLE`（503）。普通券或不存在的活动返回 `NOT_FOUND`。

## 实现与兼容边界

- 沿用 `tb_voucher` 与 `tb_seckill_voucher`；受理前读取活动类型、状态、库存行和起止时间。只接受 `type=1`。账号必须为 `ACTIVE`，具备 USER 或 MERCHANT 角色且不具备 ADMIN 角色。当前数据模型没有学生认证或活动名单，因此本阶段不声称具备这些资格条件。数据库身份和活动状态校验是请求时快照，不能与 Redis 脚本组成跨系统原子事务。
- 沿用状态 `1` 上架、`2` 下架、`3` 过期。按现有数据库 UTC 配置解释活动时间；创建时截到整秒，与旧 `TIMESTAMP` 列一致。仅接受该列可表达的 1970～2038 年范围，结束时间须晚于开始时间。
- 保留 `seckill:stock:{id}`（String）、`seckill:order:{id}`（Set）和共享 `stream.orders`，新增 `seckill:activity:{id}`（Hash）和 `seckill:request:{id}`（Hash，用户到原订单 ID）。四个活动 key 采用活动结束后 24 小时的固定绝对过期时刻；共享 Stream 无 TTL。旧活动只有库存 key 而无规则时，受理会拒绝，不能自动重建库存或清空历史参与记录；安全恢复需要单独设计和核对真实数据库、Redis 状态。
- 活动两张表写入成功后，事务 `afterCommit` 才调用初始化 Lua。回滚不会发布 Redis 规则。初始化发现任一活动 key 已存在便拒绝覆盖。提交后的 Redis 初始化失败或结果不确定时返回 503，提示“活动已保存”，必须先核对原活动，不能直接重建；数据库提交无法随之回滚。
- 受理 Lua 使用 Redis `TIME`、显式 `KEYS`、字符串订单 ID 和原 Stream 字段 `userId/voucherId/id`。它先核验 key 类型，再写 Stream，成功后扣 Redis 库存并记录参与及原 ID。脚本及 key 布局当前针对单机 Redis，跨槽 Redis Cluster 不受支持。Lua 运行错误不会自动回滚此前已执行的写操作；Redis 持久化、Redis 与 MySQL 最终一致性及消费失败补偿仍需后续阶段处理。

## 实际验证

原临时工作区被系统清空后，从远程提交 `4323922` 恢复基线，并逐文件核对 131 个 Git blob 哈希；Phase 3A 改动依据此前工作记录恢复。恢复后的代码使用 JDK `1.8.0_492`、隔离 Maven 缓存运行离线 `clean test`，通过 96 项默认测试（0 失败/错误，4 项手工外部服务用例按设计跳过）。另显式运行 `FlashSaleRedisScriptIT`，通过 10 项隔离 Redis 测试（0 失败/错误/跳过）。本次验证为补齐本机缺失依赖，将 Surefire 插件版本通过命令行临时指定为 `3.1.2`，未修改项目 POM。

隔离 Redis 测试自行启动随机本机端口、禁用持久化的独立 Redis 8.6.2 并在结束后关闭，不接触开发环境共享 Redis。覆盖：64 位 ID 保真、原 ID 重放、资格/状态/时间拒绝、损坏或缺失规则、错误 Stream 类型、真实 `XADD` 失败不扣库存、历史参与记录、固定 TTL、40 个用户竞争 3 件库存恰好 3 次受理、同一用户 30 次并发恰好一个新事件。Redis 6 兼容方式已按命令复制 API 处理，但没有在 Redis 6 实例实测。这是正确性回归，尚未进行实际压测。

没有执行真实 MySQL 的新活动创建及抢购端到端、V002 实库迁移、消费者重启恢复、订单状态闭环或性能验证。测试通过不能代替这些验收。

设计说明见 [活动受理取舍](../learning/flash-sale-admission.md)。
