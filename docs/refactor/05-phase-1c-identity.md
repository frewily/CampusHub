# Phase 1C 项目标识迁移验证记录

## 1 阶段范围

本阶段将代码与构建身份从旧教学项目迁移为 CampusHub，同时保留现有数据与接口兼容面。不修改业务模型、Controller 路径、数据库表、Redis key 或消息语义。

完成内容：

- Maven 坐标迁移为 `io.github.frewily:campushub`。
- Maven 名称改为 `CampusHub`，描述改为校园生活、周边商户和限量活动后端。
- Spring 应用名改为 `campushub`。
- Java 主代码和测试根包迁移为 `io.github.frewily.campushub`。
- 启动类和手工集成测试分别改为 `CampusHubApplication`、`CampusHubApplicationTests`。
- 同步 MyBatis Mapper 扫描、XML namespace、type alias 和日志包配置。
- 删除生成器作者标记、注释掉的旧实现、未使用的自代理字段和对应 AspectJ 依赖。
- 增加旧数据库、接口和 Redis 兼容边界说明。

## 2 兼容策略

以下内容保持不变：

- 默认数据库 schema `hmdp`、初始化脚本文件名和现有 `tb_*` 表。
- Controller 的 HTTP 路径和方法映射。
- 请求响应对象字段和现有 JSON 结构。
- Redis key、Stream `stream.orders` 和消费组 `g1`。
- `Shop`、`Blog`、`Voucher` 等仍需业务迁移的旧领域类名。

Java 内部包名不是对外接口，因此不保留一套重复的 `com.hmdp` 转发包。外部兼容项的后续迁移必须配套数据库脚本、接口版本或 Redis 读写过渡。

## 3 自动测试

执行命令：

```bash
JAVA_HOME=/path/to/jdk8 ./mvnw -o -Dmaven.repo.local=/private/tmp/campus-m2 clean test
```

结果：17 个测试，0 失败，0 错误，1 个依赖外部服务的手工集成测试按设计跳过。构建日志显示：

- Maven 坐标为 `io.github.frewily:campushub`。
- 项目名称为 `CampusHub`。
- 71 个主源码文件和 8 个测试源码文件均从新根包编译。
- 单元测试类均从 `io.github.frewily.campushub` 下执行。

## 4 真实启动验证

使用 JDK `1.8.0_492`、本机 Redis 和随机 HTTP 端口执行 `spring-boot:run`。结果：

- 启动日志显示主类为 `io.github.frewily.campushub.CampusHubApplication`。
- Spring Web 上下文、MyBatis Mapper 扫描、Redisson 和 Spring Data Redis 初始化成功。
- 订单 Stream 消费组 `g1` 正常识别，没有出现 `NOGROUP`。
- Tomcat 在随机端口启动。
- 进程收到中断信号后正常结束，Maven 退出码为 0。

## 5 静态审查

- 主代码、测试、Mapper XML、Maven 和 README 中没有残留 `com.hmdp`、`HmDianPing` 或 `hm-dianping` 产品身份。
- 历史审计文档保留旧名称，用于说明当时事实，不代表当前项目身份。
- Controller 映射集合与迁移前一致。
- 旧 schema 名只出现在数据库兼容配置、初始化脚本和相关说明中。
- `git diff --check` 和敏感信息扫描必须在提交前再次执行。

## 6 尚未完成

- 旧领域类、数据库表和 Redis key 尚未迁移为新的领域词汇。
- 旧数据库初始化数据仍包含教学样例，数据治理不属于本阶段。
- 认证、授权、错误模型和请求校验仍属于 Phase 2。
- 未执行完整业务端到端、并发恢复或性能测试。

## 7 阶段结论

CampusHub 已拥有独立的构建、Spring 和 Java 代码身份；自动测试与真实启动验证通过。现有数据与 HTTP 兼容面被明确保留，后续阶段可以在不混入本次机械迁移的前提下继续业务重构。
