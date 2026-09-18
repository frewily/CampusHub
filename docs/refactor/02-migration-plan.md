# CampusHub 渐进迁移计划

## 1 执行规则

每个阶段都遵循同一闭环：

1. 明确范围和不做事项。
2. 修改代码与文档。
3. 使用项目规定 JDK 编译并运行相关测试。
4. 执行敏感信息扫描、`git diff --check` 和变更审查。
5. 创建一个范围清晰的 Git 提交。
6. 审查该提交是否满足阶段出口条件；通过后才进入下一阶段。

提交不等于验证完成。若外部依赖阻塞测试，必须在阶段记录中写明“未验证”，不能把静态检查写成运行成功。

## 2 阶段顺序

### Phase 0 现状审计

范围：只读审计并生成当前状态、目标架构和迁移计划。

交付物：

- `docs/refactor/00-current-state.md`
- `docs/refactor/01-target-architecture.md`
- `docs/refactor/02-migration-plan.md`

出口条件：文档覆盖架构、调用链、教学痕迹、技术债、验证证据和后续依赖关系；提交后复审不修改核心业务。

建议提交：`docs: complete phase 0 repository audit`

### Phase 0.5 稳定开发基线

审计显示当前代码存在 P0 正确性问题，因此在领域重命名前增加一个稳定化阶段。

范围：

- 明确并固定支持的 JDK；推荐先保证 Java 8 基线可复现，再单独评估 Java 17。
- 增加 Maven Wrapper、最小 README、`.env.example` 和 test 配置。
- 将现有“工具型测试”与自动测试分离，建立不依赖个人环境的上下文或单元测试入口。
- 修复 Stream 消费组初始化、消费者停止条件和测试启动时的日志风暴。
- 不改业务名，不引入 Spring Security、MQ 或 Elasticsearch。

出口条件：全新环境按文档能编译；自动测试不会要求开发者已有 Redis group 或真实 MySQL 密码；相关命令结果写入阶段记录。

建议提交：`chore: establish reproducible development baseline`

### Phase 1 业务模型与 P0 业务修复

#### Phase 1A 领域模型决策

- 生成 `docs/domain-model.md`。
- 明确 User、Merchant、Store、Post、Promotion、FlashSale、Order 的边界和现有表映射。
- 明确 USER、MERCHANT、ADMIN 的资源权限关系。
- 记录暂不改名的类和原因。

出口条件：领域词汇、实体关系、业务规则和迁移兼容策略可由代码映射验证。

建议提交：`docs: define CampusHub domain model`

状态：已完成，并通过提交后范围、证据和格式审查。

#### Phase 1B 正确性修复

- 修复 Feed 写入用户 key。
- 统一关注 Redis 数据结构，并为数据库关注关系增加唯一约束。
- 修复逻辑过期缓存冷启动与重建格式。
- 为订单增加一人一单唯一约束。
- 修复 Stream 失败后错误 ACK；明确可重试与不可重试结果。
- 增加上述缺陷的回归测试。

出口条件：每个 P0 缺陷都有失败前证据、修复代码和回归测试；数据库迁移可重复执行。

建议提交：`fix: stabilize feed cache and flash sale invariants`

状态：已完成实现与验证。Feed、关注、逻辑过期缓存、订单 ACK/事务及数据库唯一约束的证据见 `docs/refactor/04-phase-1b-correctness.md`。

#### Phase 1C 项目标识迁移

- 更新 Maven 坐标、应用名和项目描述。
- 迁移启动类和根包时使用小步兼容方式；不在同一提交混入业务功能。
- 清理无价值的教学注释和注释掉的旧实现，将必要解释迁入 `docs/learning/`。

出口条件：全仓库不再以黑马点评作为产品身份；构建和现有接口兼容性有验证记录。

建议提交：`refactor: adopt CampusHub project identity`

状态：已完成实现与验证。Maven、Spring 和 Java 根包身份及兼容边界见 `docs/refactor/05-phase-1c-identity.md`。

### Phase 2 后端基础工程

建议拆成四个可独立审查的提交：

1. `refactor: introduce typed api errors and validation`
   - `BusinessException`、错误码、统一响应、参数校验、全局异常处理。
2. `feat: implement session lifecycle and logout`
   - 删除验证码日志、实现登出、清理 ThreadLocal、限制验证码发送和失败次数。
3. `feat: enforce role and resource authorization`
   - 选择并引入 Spring Security；实现 USER、MERCHANT、ADMIN 和资源归属校验。
4. `refactor: separate api models from persistence entities`
   - 优先处理商户写入、用户资料、动态发布和活动创建，不机械创建四套对象。

出口条件：匿名写接口关闭；越权、禁用用户、token 失效和登出均有测试；日志不含验证码、密码和完整 token。

### Phase 3 限量活动系统

#### Phase 3A Redis 与 Lua 规则

- 增加活动状态、起止时间、用户资格和 key TTL。
- 定义重复请求响应和订单受理状态。
- 对 Lua 返回码和 Redis 异常做完整处理。

#### Phase 3B 可靠消费

- 先完善 Redis Stream：唯一消费者名、pending claim、重试次数、失败记录、幂等和补偿。
- 完成后编写 ADR，比较继续使用 Redis Stream 与迁移 RabbitMQ/RocketMQ 的成本。
- 只有 ADR 证明需要时才引入一个专业 MQ。

#### Phase 3C 订单闭环

- 增加订单状态查询、超时、取消或核销中的最小真实闭环。
- 验证 Redis 成功而 DB 失败、重复消费和消费者重启恢复。

出口条件：并发测试确认不超卖、不重复建单；失败路径有可观察状态。尚未压测时明确写“尚未进行实际压测”。

### Phase 4 缓存与搜索

#### Phase 4A 缓存治理

- 商户缓存只保留一条正式策略。
- 提交后失效、空值、热点重建和 TTL 抖动有测试。
- 记录缓存命中率的测量方式，但没有实测不得填写数字。

#### Phase 4B 搜索决策

- 先实现校园商户搜索用例和 MySQL 基线。
- 数据量、查询维度或学习目标确实需要时，再加入 Elasticsearch。
- 若引入，必须同时交付 Mapping、初始化、增量同步、失败重试和全量重建。

出口条件：搜索能力与数据同步方式可实际运行；README 不把 ES 描述为当前规模的必需品。

### Phase 5 工程化

- 增加 dev/test/prod 配置。
- 增加 MySQL、Redis 和实际采用中间件的 Docker Compose。
- 增加健康检查、初始化脚本和可重复启动说明。
- 决定图片使用本地存储适配器还是对象存储适配器，移除硬编码 Windows 路径。
- 重写 README，但只描述已完成能力。

出口条件：在干净环境按 README 可以启动依赖、初始化数据、运行应用和完成一个 smoke test。

### Phase 6 质量 可观测性与性能

- 核心测试：登录、权限、缓存、Feed、限量活动、订单、幂等和失败恢复。
- 使用 Testcontainers 或等价隔离环境完成集成测试。
- 增加 Actuator、Micrometer 和 Prometheus；Grafana 仅在有实际仪表盘需求时加入。
- 选择 k6、JMeter 或 wrk 之一，保存机器、参数、持续时间和原始结果。
- 生成 `docs/performance/` 和 `docs/interview/`，简历亮点只引用真实实现与实测数据。

出口条件：指标可采集，压测可复现，所有 QPS、P95、P99 和提升比例均能追溯到原始测试记录。

## 3 每阶段审查清单

### 代码与行为

- 变更是否只覆盖本阶段范围。
- 是否保留了尚未被验证替代的旧逻辑。
- Controller 到数据库、Redis 或消息的完整调用链是否重新检查。
- 是否新增了越权、并发、事务或缓存一致性风险。

### 验证

- 使用规定 JDK 执行编译。
- 运行与变更相关的单元和集成测试。
- 对数据库迁移执行正向和重复执行检查。
- 对并发修复执行能够失败旧实现的回归测试。
- 运行 `git diff --check` 和敏感信息扫描。

### 文档与学习

- 更新阶段状态和实际验证结果。
- 在 `docs/learning/` 记录重要设计、替代方案、trade-off 和面试追问。
- 不把“代码已写”“能够编译”“测试通过”“压测通过”混为同一结论。

## 4 当前状态

- Phase 0：已完成，并通过提交后范围、证据和格式审查。
- Phase 0.5：已完成，并通过提交后范围、证据和格式审查。验证记录见 `docs/refactor/03-phase-0.5-baseline.md`。
- Phase 1A：已完成，并通过提交后范围、证据和格式审查。
- Phase 1B：已完成实现与验证，验证记录见 `docs/refactor/04-phase-1b-correctness.md`。
- Phase 1C：已完成实现与验证，验证记录见 `docs/refactor/05-phase-1c-identity.md`。
- Phase 2 至 Phase 6：尚未开始。
- 实际性能数据：尚未进行实际压测。
