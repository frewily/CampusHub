# 门店详情 Cache Aside 与失效恢复

## 不能只把 DEL 放进事务

数据库事务不能回滚 Redis 删除。提交前删除后，另一个请求可能读取旧数据库值并回写缓存；甚至门店事务回滚了，缓存却已被改变。事务 outbox 让“这个门店需要失效”的事实与业务写入一起提交，afterCommit 才尝试 Redis，后台重复处理持久记录。

Spring afterCommit 时事务资源可能仍绑定。回调里直接 DELETE outbox 不能想当然认为是新的已提交事务。本实现回调只操作 Redis，数据库完成确认交给独立调度调用，避免“代码执行了但完成标记没提交”。

## DEL 成功也不够

旧 loader 在失效前读取 DB，失效后才返回时，普通 SET 会让旧值复活。UUID epoch 给每次失效一个新版本；loader 发布时必须仍拥有原版本与锁 token。丢锁、过期 owner 或失效过的读只能重新尝试或返回 503，不能污染后续读。

DB event generation 用于 outbox 的 CAS 完成，不是 Redis epoch。一个旧事件可以被重试；每次 Redis 调用必须是新 epoch，否则旧 worker 把版本改回旧值，会出现 ABA，旧 loader 又可能发布成功。

## Lua 没有回滚

先 DEL 再改 epoch，若改 epoch 失败，旧 loader 仍能发布；先改 epoch 再 DEL，即使 DEL 权限失败留下旧数据，reader 也能通过 envelope 版本不匹配忽略它。原子不交错不等于命令错误回滚，更不等于持久化或 failover 安全。

## 为何不继续逻辑过期

逻辑过期可用性更高，但永久旧值、后台重建任务与更新事务的协调成本更高。本阶段选择短物理 TTL 和有界同步重建，让过期旧值不会无期限服务。代价是故障或热点竞争可能 503，冷请求需要 DB；没有数据证明这比旧策略更快。

空值 TTL 更短，创建事务也必须失效生成的 ID。不能只更新正值：先查不存在、后创建的门店会被负缓存遮蔽。

## 不承诺强一致

DB commit 与 Redis 失效仍有间隔，重叠读取仍可能看到旧数据。版本防护控制旧结果回写，不是跨库线性一致或 exactly-once。活跃事务的旧快照不允许写公共缓存；Redis 故障时也不允许所有请求无锁回源。若产品需要更强一致性或故障可用性，应重新定义成本和验收，不堆叠口号。

## 面试追问

- 更新 outbox INSERT 失败？业务数据与 outbox 同事务回滚，Redis 不动。
- 已提交但 Redis 不可达？DB 修改成立，pending 记录保留；恢复前缓存 TTL 仍是有限的，但没有请求/恢复 SLA。
- 旧 worker 确认新 event？DB generation CAS 不匹配，不能删新 pending。
- 丢失 epoch 但 data 还在？版本保护可能失效；必须保护 key/备份单元，不能假定本地测试覆盖持久化回退。
- 竞争请求怎么办？等有限窗口、看其他 loader 的结果，超预算 503，不递归、不抢别人的锁、不无条件 DB fallback。
- 命中率从哪里来？仅统计门店 reader 初次命中/miss 的窗口差值；Redis 全局 keyspace_hits 混合了登录/活动，不能替代业务指标。

实际接口、TTL、迁移与测试边界见 [Phase 4A](../refactor/13-phase-4a-shop-cache-governance.md)。
