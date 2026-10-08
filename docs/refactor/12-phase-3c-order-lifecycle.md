# Phase 3C 订单查询、取消与库存恢复

## 本阶段范围

交付最小真实闭环：Redis 受理 → MySQL 未支付订单 → 本人查询/取消 → 数据库库存回补与 outbox 同事务提交 → 后台 Redis 库存补偿。

不实现支付、退款、核销或自动超时取消；不允许取消尚未落库的受理请求。失败消费仍保留原预留，仅支持 Phase 3B 的受控原 ID 前向恢复，不能因为超时或 DLQ 就自动返库存。Phase 4 未开始。

## API 与权限

保留 `POST /voucher-order/seckill/{voucherId}` 的数字 `data`，兼容增加字符串 `orderId`。JavaScript 客户端应使用字符串字段，不能先解析超出安全整数范围的数字再转字符串。

- `GET /voucher-order/{id}?voucherId=9`：查询本人订单/受理状态。
- `POST /voucher-order/{id}/cancel?voucherId=9`：取消本人已持久化的未支付限量活动订单。

路由使用现有 Token 认证与个人参与授权；service 再检查当前用户、账号 ACTIVE 与角色，SQL 同时限定订单 ID、user_id、voucher_id。MERCHANT 可按既有参与规则使用自己的订单；ADMIN 即使兼有 USER 也不能走个人订单路由。匿名 401、越权/不存在 404、无效 ID 或缺少 voucherId 400；未落库/非未支付订单取消返回 409。状态依赖不可用返回 503，不假装订单不存在。

成功响应保留 `success/data` 包装。新查询 DTO 包含字符串 `orderId/voucherId`、`status`、`cancellationCompensation`、`createTime/updateTime`；不包含 userId、支付信息、失败 payload 或内部错误文本。时间沿用 UTC 的 LocalDateTime，客户端不能当成本地时区。

| 数据依据 | status | cancellationCompensation |
| --- | --- | --- |
| 本人 Redis 受理记录，未找到 DB 订单 | ACCEPTED | null |
| 同一受理的终结失败且未 redrive | REQUIRES_REVIEW | null |
| DB 原状态 1/2/3 | PENDING_PAYMENT / PAID / REDEEMED | null |
| DB 原状态 4 | CANCELLED | PENDING / COMPLETED / REQUIRES_REVIEW / UNTRACKED |
| DB 原状态 5/6 | REFUNDING / REFUNDED | null |

DB 优先；DLQ 不覆盖已持久化订单事实。先校验 `seckill:request:<voucherId>[currentUserId] == orderId`，再读取辅助失败索引及 DLQ，必须三字段 id/userId/voucherId 完全匹配。补充索引为既有 `stream.orders.failures` Hash 的 `order:<orderId> → deadId`，不替换 sourceId 索引。redrive 后回到 ACCEPTED；再次终结失败更新辅助索引。

历史 Phase 3B 已归档但未建立辅助索引的请求可能仍显示 ACCEPTED，本阶段不自动回填历史索引。受理 key 在活动结束 +24h 到期且无 DB 行时，404 仅表示没有可授权的查询依据，不能证明业务失败。

## 取消事务与兼容规则

先应用 `V003__add_order_cancellation_outbox.sql`。迁移创建 `tb_order_cancellation`，保留原订单状态和 `(user_id,voucher_id)` 唯一约束；重复执行成功不会重建表。IF NOT EXISTS 不等于能纠正已有错误结构，上线前需检查结构与索引。

单个事务顺序：owned SELECT FOR UPDATE → 只允许 status=1 且限量活动 → 条件更新 status=4 → DB stock+1（保护非法/溢出库存）→ 插入原订单 ID 的 PENDING outbox。任一步失败全部回滚。Redis 不在事务中写入，HTTP 成功只代表取消的 DB 提交，补偿 PENDING 不能解读为 Redis 已完成。

重复取消 status=4 只读现状，不再增加库存、不重复插入 outbox。历史已取消且没有 outbox 的订单为 UNTRACKED，不自动补造补偿。保留原参与 Set、user→order Hash 和订单 ID，所以本人不能取消后再次抢购；其重试仍取原已取消订单。释放库存可供其他用户参与。

## 后台补偿与故障处理

`ORDER_CANCELLATION_RECONCILER_ENABLED` 默认 true。每 5 秒扫描最多 32 条到期 PENDING；CAS 获取 30 秒租约与随机 token，在 Redis 调用前增加 attempts，最多 10 次，失败后固定 5 秒再试。最后一次崩溃也在租约过期后进入 REQUIRES_REVIEW，不无限重试。时间和批量/预算为未调优默认值，不是性能承诺。

完成、重试、转核对均限定 lease token，旧 worker 不能覆盖新 owner。Redis 响应丢失可重复调用；Redis 完成后 DB 写回失败保留租约，过期重新处理。仅保留安全原因码/异常类名，不记录异常文本。

`release-cancelled-order.lua` 只允许已提交的取消 outbox 调用。KEYS 为 stock、activity、request、released，仍为单机 Redis 布局。校验类型、原固定截止、受理订单 ID、整数库存及上界，然后写入 `PENDING:<userId>` marker → 设置原截止 TTL → 库存 +1 → 写入 `DONE:<userId>`。

| Lua 返回 | worker 处理 |
| --- | --- |
| 0 已回补 | COMPLETED / RELEASED |
| 1 已有匹配 DONE marker | COMPLETED / ALREADY_RELEASED |
| 2 原截止已过 | COMPLETED / EXPIRED，不重建活动 key |
| 3 坏状态、PENDING 或未知 marker | REQUIRES_REVIEW |
| 4 不匹配原预留 | REQUIRES_REVIEW |
| 异常/无确认结果 | 有界重试，超预算转核对 |

COMPLETED / EXPIRED 是“不再对过期运行态写入”，不声称实际回补了 Redis 库存。Lua 不交错执行但没有错误回滚：marker 后发生 ACL 拒绝等故障会保留 PENDING，库存可能未增或已增，重试必须转核对，不能再次增加或假报完成。

真实故障回归先修正测试 ACL 的 key 权限，确认认证用户与 INCRBY 拒绝；旧脚本在 marker 已写、库存未增后重试误判成功。修复通过 PENDING/DONE 区分完成状态。另复现 MySQL 全局 +08 时 outbox 默认本地时间与 UTC 到期查询错配；Mapper 插入显式 UTC，JDBC 强制会话 UTC 后回归。

自定义 `DB_URL` 必须保留 `serverTimezone=UTC&forceConnectionTimeZoneToSession=true`；仅设置解码时区不足以统一 SQL CURRENT_TIMESTAMP/TIMESTAMP。外部手写 outbox 需使用 UTC，不能绕过取消事务直接补造记录。

## 人工核对与上线前要求

先确认环境、停住相关自动处理并保留证据，按 order ID 核对 DB 状态、库存事务、outbox、Redis 原预留与 marker。PENDING marker 不能单独证明库存是否已加；不得删 marker 后盲目再试、清参与记录、覆盖整份库存或把失败状态改成 COMPLETED。需要可靠事务/运行证据才可决定修复，证据不足保留 REQUIRES_REVIEW。本阶段没有自动人工修复接口。

上线前还需 outbox/DLQ 积压与最老年龄监控、容量与保留策略、SQL/Redis ACL、持久化与恢复演练、时钟/时区一致性。Redis key 的单独丢失、OOM、持久化回退、failover、跨槽 Cluster 均未验收；若 DONE marker 丢失而预留存活，幂等保护不再可靠。不得把本地无持久化测试写成生产可靠性证明。

## 验证方式与边界

使用 JDK 1.8.0_492，命令行选择 Surefire 3.1.2，POM 不变。默认测试不访问外部服务；手工实验按设计跳过。显式 IT 启动随机 localhost 端口、私有目录的 Redis/MySQL，并结束关闭；不读取现有开发凭据，不连接共享服务。

最终干净默认 suite：143 项，0 失败/错误，4 项手工外部服务用例按设计跳过。隔离 MySQL + Redis suite：15 项，0 失败/错误/跳过，包括旧 lease token 不能完成、重试或改写新 owner 的状态。

最终隔离 Redis 回归：35 项，0 失败/错误/跳过（Phase 3A 10 项、Phase 3B 16 项、取消脚本 9 项）。ACL 中途失败、库存已增但 PENDING marker、未知/旧 marker 均不重复回补或假报完成。故障前证据用例为 `aclFailureAfterMarkerWriteRequiresReviewOnRetry`（旧实现返回 1，期望 3）和 `outboxDueDoesNotDependOnServerLocalTimezone`（旧实现到期条数为 0，期望 1）。

```bash
./mvnw -Dmaven-surefire-plugin.version=3.1.2 clean test
./mvnw -Dmaven-surefire-plugin.version=3.1.2 \
  -Dtest=OrderCancellationRedisIT,OrderStreamRedisIT,FlashSaleRedisScriptIT test
./mvnw -Dmaven-surefire-plugin.version=3.1.2 -Dtest=OrderLifecycleMySqlRedisIT test
```

本机 MySQL 9.6.0、Redis 8.6.2；MySQL 用精简合成 schema，没有加载历史用户数据。真实 Mapper、事务、唯一键与 Redisson 用户锁覆盖受理/落库/取消/补偿、20 用户争 3 库存、20 次同用户重试、8 路取消与补偿、真正 INSERT/outbox trigger 故障回滚、提交后未 ACK 的 worker 替换恢复、Redis 完成未写回 outbox 的租约恢复、坏状态/预算终结、时区与 V001/V002/V003 正向重复迁移。

HTTP 证据分开记录：隔离 DB/Redis IT 使用 MockMvc + 真 Token filter + 真业务链；完整 Spring Security 路由契约测试使用 mocked 业务依赖。两者不等于真实网络监听的全应用部署。

目标 MySQL 8、Redis 6 真机兼容、完整历史数据库迁移、多进程真实崩溃/failover、部署 smoke、压测仍未验收。并发用例验证正确性，不提供 QPS/P95 等指标；**尚未进行实际压测**。

学习说明见 [取消与恢复设计](../learning/order-cancellation-recovery.md)。
