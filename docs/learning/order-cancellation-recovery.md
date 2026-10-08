# 订单取消与可靠库存补偿

## 为什么用事务 outbox

直接在 DB 事务里写 Redis 不能提供跨系统原子提交：Redis 成功而 DB 回滚会多放库存；DB 提交而 Redis 不可达则取消已经成立但库存未回补。

因此同一个 MySQL 事务只写订单取消、数据库库存和 outbox。提交后后台从 durable outbox 恢复 Redis，HTTP 区分 CANCELLED 与补偿 PENDING。数据库是订单事实，Redis 是活动运行态，不靠失败超时猜测事务结果。

## 幂等有两个层次

DB 用订单行锁、status=1 条件更新、order_id 主键和业务唯一约束确保取消只生效一次。worker 的租约 token 防旧 owner 写回，但租约可能在 Redis 调用中到期，所以 Redis 还需独立幂等 marker。

marker 不能在回补前直接代表“成功”。Redis Lua 错误不回滚，ACL 拒绝可留下 marker 而库存未加；库存已加、最后 DONE 写入失败也可能留下 PENDING。PENDING 表示部分执行不确定，DONE 才代表完成；自动重试遇到 PENDING 转人工核对，牺牲自动可用性来避免重复库存。

## 为什么取消不清一人一单记录

本阶段规则仍是一人一活动一个订单，而不是一个有效订单。取消归还库存供其他人参与，本人重试仍得到原订单 ID。删参与 Set/request Hash 会破坏重试身份，并与原数据库唯一约束冲突。若以后要允许重新报名，需另一个产品决策与版本化参与模型，不能只删 Redis key。

## 故障预算与时间

attempts 必须在外部调用前增加，否则连续进程崩溃不计数。最终尝试崩溃也要由过期租约转人工核对。claim、complete、retry 使用 DB UTC 时钟，JDBC session 也必须 UTC；serverTimezone 仅用于客户端时间解释不是服务端 SQL 时区保证。

## 查询不能泄露失败内部信息

查询只授权本人原受理/订单。DLQ 原始 payload 与 exception 信息不面向用户；只投影 ACCEPTED/REQUIRES_REVIEW。DB 已存在时优先返回订单事实，避免旧 DLQ 覆盖成功事实。缺失历史辅助索引或 TTL 到期意味着观察依据不足，不能据此宣称业务失败。

## 面试追问

- Redis 成功但 DB 完成标记失败？租约过期重复脚本，DONE 不再增库存。
- marker 是 PENDING 时直接删除重试？不行，库存可能已增，必须先核对证据。
- 取消事务里 outbox INSERT 失败？订单与数据库库存一并回滚，Redis 没有写入。
- 已支付订单呢？本阶段拒绝取消，不伪造支付/退款链路。
- 活动运行态已经过期？不重建，outbox 标记 EXPIRED，DB 取消仍是事实。
- 能宣称 exactly-once 或生产就绪吗？不能。DB/Redis 各有幂等防护，不涵盖独立 key 丢失、持久化回退或 failover；本地并发测试不是压测。

具体 API、脚本返回码、验证和部署边界见 [Phase 3C 阶段记录](../refactor/12-phase-3c-order-lifecycle.md)。
