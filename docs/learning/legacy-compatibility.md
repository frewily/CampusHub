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
- HTTP 路径、请求参数和响应结构保持不变。
- Redis key、Stream 名称和消费组名称保持不变。
- 旧业务类名如 `Shop`、`Blog`、`Voucher` 暂时保留，并按领域模型中的映射逐阶段迁移。

这些名称属于现有数据和接口的兼容约束，不再代表项目产品身份。后续修改必须配套数据迁移、接口版本边界或 Redis 读写过渡，不能只做字符串替换。

## 本阶段清理原则

删除生成器作者标记、注释掉的旧实现和只复述代码步骤的教学注释。保留解释事务、缓存、幂等和兼容取舍的注释；尚未重构的实现不借身份迁移阶段改变业务行为。
