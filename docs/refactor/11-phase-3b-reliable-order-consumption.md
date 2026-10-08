# Phase 3B 可靠订单消费与验证记录

## 范围与根因证据

只处理受理后的异步消费，不修改 Phase 3A 受理 HTTP 契约，不加入订单状态、支付、取消或核销接口，也不引入专业 MQ。MySQL 仍是订单事实来源，`ACCEPTED` 仍不代表落单成功。

旧实现固定消费者 `c1`，只在捕获处理异常后读取自己的 pending，启动时只读 `>`；其他消费者的 pending 无法恢复。一条毒消息会在内层循环无限重试。实施前，在隔离 Redis 实际执行“读取事件但不 ACK → 模拟重启 → 继续读新事件”：PEL 中仍有 1 条，旧名称和新名称的 `>` 均读不到，新名称读取自己的 `0` 也读不到。只有 `XCLAIM` 才能转移归属。该证据保留为 `OrderStreamRedisIT.reproduceOldConsumerRestartLeavesPendingInvisibleToNewMessageRead`，不是只凭静态检查推断。

## 实现、兼容与处理规则

- 将 Stream 生命周期从 `VoucherOrderServiceImpl` 分离至 `OrderStreamConsumer` / `OrderStreamQueue`，保留 `stream.orders`、`g1`、事件 `id/userId/voucherId`、原订单 ID 和业务表名。每个进程用 `order-UUID` 独立消费者名。禁用开关仍生效，启动用 `0-0 MKSTREAM` 幂等建组。
- 启动后第一轮及每一轮都扫描 pending，不依赖新消息或先前异常。每页最多 32 条，游标越过尚未空闲的条目，防止热的第一页饿死后续消息。使用 Redis 6 命令 `XPENDING` + `XCLAIM`，由 Redis 在 claim 时复核空闲时间；未使用 `XAUTOCLAIM`、`FORCE` 或 `DELCONSUMER`。
- 每轮先处理接管记录，再阻塞最多 2 秒读取 1 条新记录。业务失败留 pending，达到上限则归档；没有无限内层重试循环。基础设施异常暂停 1 秒后重试。重试采用固定空闲间隔，不是指数退避，也没有经过生产调优。
- `ORDER_CLAIM_IDLE_MS` 默认 60000，最低 1000；`ORDER_MAX_ATTEMPTS` 默认 5，范围 1～100。尝试次数包含首次处理，在数据库调用前用 Lua 写入 `stream.orders.attempts`（Hash，source ID → 次数），进程崩溃也计数。若计数已经用尽，接管者不再写数据库，直接归档待核对。尝试预算是自动处理上限，不是数据库失败次数或 Redis 投递次数。
- BEGIN、SUCCESS、FAILURE 脚本复核 PEL 当前 owner。归属已经变化的旧 worker 不再 ACK 或归档。该检查不能撤销已经开始的数据库操作，也不能让 claim 成为数据库执行锁；仍依赖原 Redisson 用户锁、数据库事务、主键及 V001 的 `(user_id, voucher_id)` 唯一约束。
- 同一用户/活动已存在且订单 ID 相同，直接幂等成功，不重复扣数据库库存；若已存在订单的 ID 不同，标记 `ORDER_ID_CONFLICT`，不谎报原受理 ID 已落库。条件扣库存失败、插入失败、锁竞争分别记录 `DATABASE_STOCK_UNAVAILABLE`、`ORDER_INSERT_FAILED`、`LOCK_BUSY`；其他异常只保留类名，不保存异常文本或堆栈。格式错误也按有界预算处理，不让实体属性绑定接受额外字段。
- 数据库事务返回之后才 SUCCESS ACK。ACK 异常不进入数据库失败分支；下一次消费重新进行幂等判断。达到预算时仍可能是“已提交但 ACK 不确定”，因此失败归档的含义是 **REQUIRES_REVIEW**，不代表数据库肯定失败。
- 达到上限时，Lua 预检 key 类型、计数和 owner，将原始字段数组 JSON 及元数据写入 `stream.orders.dead`，写入 `stream.orders.failures`（source ID → dead ID），之后 ACK 源消息并清理计数。重复终结不重复归档；已有索引必须指向真实匹配的 DLQ 记录。DLQ 类型错误或实际 `XADD` 失败时不会 ACK 源消息。
- 正常成功清理尝试计数；失败记录和重投审计不自动过期。关闭时中断读取，最多等 10 秒，超时日志提示处理可能仍在完成；不删除消费者及其 pending。没有承诺数据库操作一定能在关闭时限内中断。

DLQ 每条记录包含：`sourceId`、`group`、`consumer`、`attempts`、`errorClass`、`failedAt`（Redis Unix 秒）、`payload`（原始字段数组 JSON）和 `reservation=RETAINED`。业务分类码与异常类名仅供排查，不据此推定事务结果。只有最终失败保存记录，没有完整逐次失败历史。错误事件的原始 payload 也属于受保护数据，必须限制 Stream/DLQ 读取权限，不能直接展示给普通用户或公开日志。

Lua 的不交错执行不等于持久化保证或运行错误回滚。类型预检、先归档后 ACK 和幂等索引覆盖常见故障；未演练 Redis OOM、ACL 中途拒绝、持久化丢失或 failover。源 Stream、计数、索引、DLQ 必须作为同一恢复单元保存。当前布局只支持单机 Redis，不支持跨槽 Cluster。

## 人工前向补偿与运维入口

**不自动返库存、不清参与记录、不改原受理 ID。** 如果数据库已经提交而消费者超时，返库存会与真实订单冲突。本阶段提供 operator-only `redrive-order.lua`，不是公开 HTTP 管理接口。

1. 查看 `XPENDING stream.orders g1`、`XINFO CONSUMERS stream.orders`、`XLEN stream.orders.dead` 和 `stream.orders.failures` 索引，按 source ID / dead ID 查到原始事件。
2. 在隔离/受控环境核对 MySQL：原订单 ID、用户/活动唯一键、库存扣减和事务结果；同一业务键不同 ID、仍有旧 worker 执行、数据库不可达或结果仍不确定时停止操作，不猜测退款条件。
3. 确认状态可安全重试后，修复依赖或数据原因，用原 DLQ ID 重投相同 `id/userId/voucherId`。已落库的相同订单 ID 会命中幂等检查；未落库的事件会重新获得一轮有限预算。畸形或超出 Java Long 范围的 payload 拒绝重投。
4. 重投前后保留核对依据。脚本要求非空 operator label 与 reason code（1～64 个字母、数字、下划线、点或短横线，不填凭据/个人敏感信息），在 `stream.orders.redrives` Hash 保存 `deadId → newSourceId`、`deadId:operator`、`deadId:reason`、`deadId:at`。操作者身份由受控 Redis ACL / 外部操作审计保证，label 不是应用认证；本阶段未构建身份审计平台。
5. 再次调用同一 DLQ ID 返回第一次的新 source ID，不再追加事件。归档及审计保留；确认最终数据库状态后才由治理流程决定清理。`redrive` 不是退款/取消，也不触碰活动库存或参与 key，即使活动运行态 TTL 已过，也只修复原来已受理的事件。

示例（**仅在完成上述核对、选定正确环境和授权后执行**；这里不替操作者选择目标）：

```bash
redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" --eval src/main/resources/redrive-order.lua \
  stream.orders stream.orders.dead stream.orders.redrives , \
  "$DEAD_LETTER_ID" "$OPERATOR_LABEL" DB_REVIEWED
```

启用 ACL/TLS 时使用环境对应的安全连接配置，不把密码放进命令历史。上线前还需设定 PEL/DLQ 积压和最老消息年龄告警、容量预算、持久化与备份恢复、保留和清理责任。禁止对存在未完成消息的源 Stream 任意 `XTRIM`/`XDEL`，禁止清理仍被失败索引引用的 DLQ，禁止直接删除有 pending 的消费者。UUID 消费者会留下元数据；仅确认已退出且 pending 为 0 后才能治理。当前没有自动容量清理和告警系统，不应视为可直接上线。

## 实际验证与待验边界

JDK `1.8.0_492`，离线干净构建：107 项默认测试，0 失败/错误，4 项手工外部服务用例按设计跳过。Surefire `3.1.2` 只通过命令行选择，POM 未改变。

隔离 Redis `8.6.2`：显式运行 `OrderStreamRedisIT` 与 `FlashSaleRedisScriptIT`，共 26 项，0 失败/错误/跳过。测试自行启动非持久化随机端口 Redis，并在结束后关闭；不连接共享 Redis 或真实 MySQL。

覆盖：旧逻辑恢复缺口复现、无新流量接管、未空闲不抢占、计数跨 worker 保留、最后尝试崩溃、过期 owner 不能 ACK/归档、DLQ WRONGTYPE / 真正 XADD 失败保留 pending、损坏 DLQ 不阻塞成功订单、计数和索引损坏 fail closed、跨热第一页扫描、64 位 ID 重投保真和重投幂等/审计、畸形重投拒绝、重投追加失败、空 Stream 建组，以及真实 Spring Bean 启动后台 worker 接管并 ACK。Spring 启动测试中的订单服务为 mock，不等于数据库端到端。默认单元测试另覆盖毒消息/单条归档基础设施失败后继续读新事件、持久化失败不错误 ACK、ACK 失败不算数据库失败、业务分类和同业务键不同 ID 拒绝。

没有执行真实 MySQL/V001/V002 迁移、真实订单事务与回滚、多实例数据库并发、跨数据库/Redis 故障、Redis 6 真机兼容、持久化恢复或性能压测。**尚未进行实际压测**；不提供吞吐/延迟数字。Phase 3C 尚未开始。

取舍见 [可靠消费学习说明](../learning/reliable-order-consumption.md) 和 [MQ 决策 ADR](../adr/0001-order-message-broker.md)。
