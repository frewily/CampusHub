# Phase 1B 正确性修复验证记录

## 1 阶段范围

本阶段只修复领域迁移前已经确认的 P0 正确性缺陷，不修改项目坐标、根包、接口路径或产品命名，也不引入新的消息中间件。

完成内容：

- Feed 写入每名粉丝自己的 Redis ZSet，而不是写入固定 key。
- 关注关系统一使用既有 ZSet 投影，共同关注按同一种数据结构求交集。
- 关注请求支持幂等写入并拒绝自关注。
- 逻辑过期缓存冷 miss 时回源，重建后继续保存逻辑过期封装。
- Redis Stream 订单只在持久化正常返回后 ACK；锁竞争和持久化失败保留为可重试失败。
- 使用 `TransactionTemplate` 保证异步消费线程内的库存扣减与订单写入处于同一事务。
- 为关注关系和一人一单增加可重复执行的数据库唯一约束迁移。

## 2 根因与回归证据

| 缺陷 | 根因 | 失败前证据 | 修复后验证 |
| --- | --- | --- | --- |
| Feed 无法形成个人收件箱 | 粉丝循环始终写入 `feed:` 固定 key | 回归测试观察到旧实现未写 `feed:42`、`feed:84` | `BlogServiceImplTest` 通过 |
| 共同关注结果错误 | 写入使用 ZSet，读取却使用 Set 交集命令 | 旧实现测试返回空结果且没有读取 ZSet | `FollowServiceImplTest` 通过 |
| 重复关注和自关注缺少保护 | 应用层直接插入，数据库没有业务唯一约束 | 旧实现会再次执行 insert，且允许关注自己 | 幂等、自关注测试和数据库唯一约束验证通过 |
| 逻辑过期缓存冷 miss 永不回源 | 缓存不存在时直接返回 `null`；异步重建写入普通对象 JSON | 旧实现冷缓存测试返回 `null`，重建值不含逻辑过期字段 | `CacheClientTest` 通过 |
| 订单失败后仍可能 ACK | 持久化异常在处理方法内被吞掉；同类调用的声明式事务不生效 | 旧实现锁竞争和持久化失败正常返回 | `VoucherOrderServiceImplTest` 通过 |

这些测试证明修复覆盖了已复现的代码路径，不等同于完整业务端到端或并发故障恢复验收。

## 3 自动测试

目标回归测试命令：

```bash
JAVA_HOME=/path/to/jdk8 ./mvnw -o -Dmaven.repo.local=/private/tmp/campus-m2 \
  -Dtest=BlogServiceImplTest,FollowServiceImplTest,CacheClientTest,VoucherOrderServiceImplTest,DatabaseMigrationTest test
```

结果：13 个测试通过，0 失败，0 错误。

完整测试命令：

```bash
JAVA_HOME=/path/to/jdk8 ./mvnw -o -Dmaven.repo.local=/private/tmp/campus-m2 clean test
```

结果：17 个测试，0 失败，0 错误，1 个依赖外部服务的旧手工集成测试按设计跳过。

## 4 数据库迁移验证

迁移脚本：`src/main/resources/db/migration/V001__add_business_unique_constraints.sql`。

在本机 MySQL 9.6 的临时隔离实例中完成以下验证：

- 首次执行成功，创建 `uk_follow_user_target(user_id, follow_user_id)`。
- 首次执行成功，创建 `uk_voucher_order_user_voucher(user_id, voucher_id)`。
- 第二次执行成功，证明成功应用后的重复执行不会重复创建索引。
- `INFORMATION_SCHEMA.STATISTICS` 返回的索引列及顺序符合预期。
- 重复关注和重复活动订单插入均被数据库唯一约束拒绝。

验证使用独立临时数据目录；实例已经停止，临时 socket、pid、日志和数据目录已经清理，没有修改现有业务数据库。若历史库已包含重复数据，迁移会明确失败并要求人工处理，不会静默删除业务记录。

## 5 真实启动验证

使用 JDK `1.8.0_492`、本机 Redis 和随机 HTTP 端口启动应用。结果：

- Spring 上下文创建成功，`TransactionTemplate` 注入无异常。
- Redisson 和 Spring Data Redis 连接成功。
- 日志确认 `stream.orders` 的消费组 `g1` 已存在，没有出现 `NOGROUP`。
- Tomcat 在随机端口启动。
- 进程收到中断信号后正常停止，Maven 退出码为 0。

首次在受限沙箱内启动时，本机 Redis 连接被操作系统策略拒绝并显示 `Operation not permitted`；使用允许访问本机服务的相同命令复验后启动成功。该首次失败不是应用逻辑失败。

## 6 静态审查与边界

- 变更保持在 Phase 1B：没有改 Maven 坐标、根包、表名或接口路径。
- 数据库唯一约束是并发场景的最终防线；应用层检查只负责快速返回和幂等语义。
- 关注 ZSet 当前在应用内求交集，适合现阶段数据规模，不宣称已经适配大规模社交图。
- Redis Stream 当前仍使用固定消费者名，尚未实现重试次数、死信、补偿或多实例消费治理。
- 逻辑过期缓存仍使用旧的简单 Redis 锁释放方式，尚未增加随机 token 和 Lua 原子释放。
- 未验证完整登录、Feed、秒杀和订单端到端链路，未执行并发压测或性能测试。

## 7 阶段结论

Phase 1B 的目标缺陷均有失败前证据、修复代码和回归测试；完整测试、隔离数据库迁移和真实应用启动均已验证。后续可靠消费、锁治理和端到端验收仍按迁移计划推进，不在本阶段宣称完成。
