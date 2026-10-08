# CampusHub 身份迁移与旧系统兼容边界

## 已迁移的项目身份

- Maven 坐标：`io.github.frewily:campushub`
- Maven 名称：`CampusHub`
- Spring 应用名：`campushub`
- Java 根包：`io.github.frewily.campushub`
- 启动类：`CampusHubApplication`

根包直接迁移，不保留重复的旧包转发类。项目仍处于单体阶段，内部 Java 包不是对外兼容接口；同时维护两套包只会增加扫描、依赖和测试歧义。

## 暂时保留的兼容面

- 数据库 schema 默认名仍为 `hmdp`，初始化脚本仍为 `src/main/resources/db/hmdp.sql`。
- `tb_*` 表名及字段名保持不变。
- HTTP 路径、有效业务字段名和统一响应包装保持不变。Phase 2D 为优先写入接口引入字段白名单与必填校验，冗余实体字段忽略，缺失或非法业务字段返回 400；具体变化见 [Phase 2D 接口记录](../refactor/09-phase-2d-api-models.md)。
- Redis key、Stream 名称和消费组名称保持不变。
- Phase 3A 保留旧库存/参与 key 和 `stream.orders` 事件字段，新增活动规则与原订单 ID 映射 key；旧活动若只有库存 key，不会自动补齐规则或重置库存。
- 抢购成功仍以数字 ID 放在 `data`，增量返回 `acceptanceStatus=ACCEPTED` 和 `replayed`。这仅表示 Redis Stream 已受理。活动状态、时间、资格与库存拒绝改为明确错误码；超时/规则异常返回 503，详情见 [Phase 3A 记录](../refactor/10-phase-3a-flash-sale-admission.md)。
- 旧业务类名如 `Shop`、`Blog`、`Voucher` 暂时保留，并按领域模型中的映射逐阶段迁移。

这些名称属于现有数据和接口的兼容约束，不再代表项目产品身份。后续修改必须配套数据迁移、接口版本边界或 Redis 读写过渡，不能只做字符串替换。

## 本阶段清理原则

删除生成器作者标记、注释掉的旧实现和只复述代码步骤的教学注释。保留解释事务、缓存、幂等和兼容取舍的注释；尚未重构的实现不借身份迁移阶段改变业务行为。
