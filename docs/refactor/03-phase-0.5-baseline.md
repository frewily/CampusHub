# Phase 0.5 稳定开发基线验证记录

## 1 阶段范围

本阶段只建立可复现的开发、测试和 Redis Stream 启动基线，不修改领域模型、接口语义、数据库结构或包名，也不引入新的消息中间件。

完成内容：

- 使用 Maven Wrapper 固定 Maven 3.9.9。
- 通过 Maven Enforcer 将当前支持范围固定为 JDK 8。
- 使用环境变量替代数据库和 Redis 的本地配置，并提供不含真实凭据的 `.env.example`。
- 将依赖 MySQL、Redis 和既有数据的旧测试标记为手工集成测试。
- 增加不依赖外部服务的手机号、验证码和密码工具单元测试。
- 在测试 profile 中关闭订单 Stream 消费者。
- 启动时自动创建 `stream.orders` 和消费组 `g1`，并兼容消费组已经存在的重启场景。
- 让订单消费者在线程中断或 Bean 销毁时退出。

## 2 验证环境

- 操作系统：macOS
- JDK：Amazon Corretto `1.8.0_492`
- Spring Boot：`2.7.18`
- Maven：Wrapper 固定的 `3.9.9`
- 外部服务：本机 Redis；本阶段未通过业务查询验证 MySQL 连接

这些结果只证明当前机器上的构建和启动基线，不代表业务接口、并发一致性或生产部署已经验收。

## 3 自动测试

执行命令：

```bash
JAVA_HOME=/path/to/jdk8 ./mvnw -o -Dmaven.repo.local=/private/tmp/campus-m2 clean test
```

结果：成功。默认测试套件执行 3 个纯单元测试，旧的外部依赖测试因未设置 `RUN_MANUAL_INTEGRATION_TESTS=true` 而跳过。

默认自动测试没有要求开发者预先创建 Redis Stream 消费组，也没有连接真实 MySQL 或 Redis。

## 4 启动与重启验证

使用随机 HTTP 端口启动应用，以免与本机其他服务冲突。

首次启动结果：

- 应用使用 Java `1.8.0_492` 和 Spring Boot `2.7.18` 启动成功。
- Redis 连接成功。
- 日志显示已创建 `stream.orders` 和消费组 `g1`。
- Tomcat 在随机端口启动。

停止应用后再次启动，结果：

- 日志显示订单 Stream 消费组已经存在。
- Tomcat 在随机端口启动。
- 观察期间没有出现 `NOGROUP`。
- 启动验证进程均由中断信号停止并以退出码 0 结束。

## 5 静态检查

- `git diff --check`：通过。
- Maven Wrapper 的 Unix 脚本具有可执行权限；Windows 脚本和 Wrapper 配置为普通文件。
- `.env` 已忽略，`.env.example` 只包含示例值和空密码。
- 当前阶段变更未加入真实密码、访问令牌或 API Key。

## 6 尚未验证

- 未执行旧的手工数据灌入与 Redis 实验测试。
- 未验证完整登录、商户、Feed、秒杀和订单接口链路。
- 未执行并发测试、故障恢复测试或性能测试。
- Redis Stream 的失败 ACK、重试、死信和补偿仍属于后续阶段，不在本阶段宣称完成。

## 7 阶段结论

Phase 0.5 已完成，并通过提交后范围、证据和格式复审。Phase 1 尚未开始。
