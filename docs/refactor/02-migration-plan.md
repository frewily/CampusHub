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
   - 状态：已完成实现与验证，记录见 `docs/refactor/06-phase-2a-api-errors-validation.md`。
2. `feat: implement session lifecycle and logout`
   - 删除验证码日志、实现登出、清理 ThreadLocal、限制验证码发送和失败次数。
   - 状态：已完成实现与验证，记录见 `docs/refactor/07-phase-2b-session-lifecycle.md`。
3. `feat: enforce role and resource authorization`
   - 选择并引入 Spring Security；实现 USER、MERCHANT、ADMIN 和资源归属校验。
   - 状态：已完成实现与当前环境验证，真实 MySQL 迁移仍待隔离环境补验，记录见 `docs/refactor/08-phase-2c-authorization.md`。
4. `refactor: separate api models from persistence entities`
   - 优先处理商户写入、用户资料、动态发布和活动创建，不机械创建四套对象。
   - 状态：已完成指定优先边界与自动验证，请求白名单、响应隔离、兼容行为和待补验边界见 `docs/refactor/09-phase-2d-api-models.md`。

出口条件：匿名写接口关闭；越权、禁用用户、token 失效和登出均有测试；日志不含验证码、密码和完整 token。

### Phase 3 限量活动系统

#### Phase 3A Redis 与 Lua 规则

- 增加活动状态、起止时间、用户资格和 key TTL。
- 定义重复请求响应和订单受理状态。
- 对 Lua 返回码和 Redis 异常做完整处理。

状态：已完成实现与当前环境验证。接口受理语义、Redis 规则、隔离 Redis 并发测试及真实数据库待验边界见 `docs/refactor/10-phase-3a-flash-sale-admission.md`。

#### Phase 3B 可靠消费

- 先完善 Redis Stream：唯一消费者名、pending claim、重试次数、失败记录、幂等和补偿。
- 完成后编写 ADR，比较继续使用 Redis Stream 与迁移 RabbitMQ/RocketMQ 的成本。
- 只有 ADR 证明需要时才引入一个专业 MQ。

状态：已完成实现与当前环境验证。pending 接管、有界重试、失败归档、原 ID 前向补偿及待补验边界见 `docs/refactor/11-phase-3b-reliable-order-consumption.md`；ADR 0001 决定当前保留 Redis Stream。真实 MySQL 端到端与生产持久化/治理验收仍待完成。

#### Phase 3C 订单闭环

- 增加订单状态查询、超时、取消或核销中的最小真实闭环。
- 验证 Redis 成功而 DB 失败、重复消费和消费者重启恢复。

出口条件：并发测试确认不超卖、不重复建单；失败路径有可观察状态。尚未压测时明确写“尚未进行实际压测”。

状态：已实现本人查询、已落库未支付订单取消和事务 outbox/Redis 补偿闭环；本机 MySQL 9.6.0 + Redis 8.6.2 隔离业务链覆盖并发、真实回滚与恢复，记录见 `docs/refactor/12-phase-3c-order-lifecycle.md`。这不等于目标 MySQL 8/Redis 6、历史数据迁移、真实网络部署或压测验收。

### Phase 4 缓存与搜索

#### Phase 4A 缓存治理

- 商户缓存只保留一条正式策略。
- 提交后失效、空值、热点重建和 TTL 抖动有测试。
- 记录缓存命中率的测量方式，但没有实测不得填写数字。

状态：已完成门店详情单一 Cache Aside 策略、物理 TTL/抖动、负缓存、有界 token 互斥与 epoch 发布栅栏，以及创建/修改同事务 outbox、提交后失效和可靠重试。当前环境验证与限制见 `docs/refactor/13-phase-4a-shop-cache-governance.md`；没有实际业务命中率或压测数字。

#### Phase 4B 搜索决策

- 先实现校园商户搜索用例和 MySQL 基线。
- 数据量、查询维度或学习目标确实需要时，再加入 Elasticsearch。
- 若引入，必须同时交付 Mapping、初始化、增量同步、失败重试和全量重建。

出口条件：搜索能力与数据同步方式可实际运行；README 不把 ES 描述为当前规模的必需品。

状态：已完成新入口 `GET /shop/search` 的 MySQL 基线：名称子串、类别、价格、评分、距离筛选/固定排序、白名单响应与单请求快照分页。直接读取 MySQL，不新增搜索索引同步链；ADR 0002 决定暂不引入 ES。当前环境验证与边界见 `docs/refactor/14-phase-4b-shop-search.md`。

### Phase 5 工程化

- 增加 dev/test/prod 配置。
- 增加 MySQL、Redis 和实际采用中间件的 Docker Compose。
- 增加健康检查、初始化脚本和可重复启动说明。
- 决定图片使用本地存储适配器还是对象存储适配器，移除硬编码 Windows 路径。
- 重写 README，但只描述已完成能力。

出口条件：在干净环境按 README 可以启动依赖、初始化数据、运行应用和完成一个 smoke test。

状态：已完成 dev/test/prod、默认依赖与可选应用 Compose、fresh 合成初始化、live/ready、本地安全图片适配器和双路径 README。229 项默认测试、82 项隔离 IT、10 组全新 Compose 实际网络检查已通过；目标部署实测 MySQL 8.4.11/Redis 6.2.24。细节见 `docs/refactor/15-phase-5-engineering.md`。这不代表旧库/图片自动迁移、所有旧业务目标版本兼容、生产高可用或压测验收。

### Phase 6 质量 可观测性与性能

- 核心测试：登录、权限、缓存、Feed、限量活动、订单、幂等和失败恢复。
- 使用 Testcontainers 或等价隔离环境完成集成测试。
- 增加 Actuator、Micrometer 和 Prometheus；Grafana 仅在有实际仪表盘需求时加入。
- 选择 k6、JMeter 或 wrk 之一，保存机器、参数、持续时间和原始结果。
- 生成 `docs/performance/` 和 `docs/interview/`，简历亮点只引用真实实现与实测数据。

出口条件：指标可采集，压测可复现，所有 QPS、P95、P99 和提升比例均能追溯到原始测试记录。

按可审查的小阶段推进，不把指标接入等同于压测完成：

- Phase 6A：请求编号、安全请求摘要、独立本机 Actuator 管理面、Micrometer HTTP/JVM/连接池指标和可选 Prometheus 实际采集。记录见 `docs/refactor/16-phase-6a-observability.md`。
- Phase 6B：核心业务质量缺口与业务诊断指标，明确缓存、订单消费及 outbox 的测量语义，补充必要的隔离故障测试。
- Phase 6C：选定一种负载工具，保存参数、机器/版本、持续时间及原始结果，建立可复现的基线并生成事实限定的性能/面试材料。
- Phase 6D：对照原始重构要求补齐 `docs/interview/project-story.md`、`questions.md`、`resume-points.md`，盘点已实现、合理裁剪、未交付与未验证。仅做文档收口，不把原文全部业务目标或抢购负载标为完成；见 `docs/refactor/19-phase-6d-delivery.md`。
- Phase 6E1：固定限量活动有限批次的身份/库存/受理与重放模型，交付脚本、账本与发布门禁并完成离线验证，记录见 `docs/refactor/20-phase-6e1-flash-sale-tooling.md`。Phase 6E2 单独执行正式隔离负载、复核原始结果与清理；6E1 不宣称实际压测。
- Phase 6E2：执行上述有限模型，发布六正式/六预热的真实身份、库存及原始统计证据；修复实际 CLI/隐私解析差异。见 `docs/refactor/21-phase-6e2-flash-sale-results.md`；不自动推进持续容量、逐单异步延迟或新产品功能。
- Phase 6F：交付当前主源码显式 MVC 路由的统一目录、认证/参数/响应说明、机器清单及映射/授权声明漂移检查；见 `docs/refactor/22-phase-6f-api-catalog.md`。不新增 Swagger/OpenAPI 依赖或业务接口，不把目录完整等同于端到端验收。

每个小阶段仍执行验证、提交、提交后审查；6A 不进入实际压测，也不宣称 Phase 6 全部完成。

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
- Phase 2A：已完成实现与验证，类型化错误、参数校验和全局异常处理记录见 `docs/refactor/06-phase-2a-api-errors-validation.md`。
- Phase 2B：已完成实现与验证，会话生命周期、登出和验证码安全记录见 `docs/refactor/07-phase-2b-session-lifecycle.md`。
- Phase 2C：已完成实现与当前环境验证，账号状态、角色、商户资源归属和待补验边界见 `docs/refactor/08-phase-2c-authorization.md`。
- Phase 2D：已完成指定优先边界与自动验证，记录见 `docs/refactor/09-phase-2d-api-models.md`。
- Phase 3A：已完成实现与当前环境验证，记录见 `docs/refactor/10-phase-3a-flash-sale-admission.md`；Phase 3C 补充本机隔离 MySQL/Redis 业务链验证。
- Phase 3B：已完成实现与当前环境验证，记录见 `docs/refactor/11-phase-3b-reliable-order-consumption.md`；Phase 3C 补充真实事务回滚与 worker 替换恢复测试，不包含进程强杀/failover 演练。
- Phase 3C：已完成实现与本机隔离验证，记录见 `docs/refactor/12-phase-3c-order-lifecycle.md`；目标版本、历史数据迁移、部署及性能边界仍待验收。
- Phase 4A：已完成门店详情缓存治理与本机隔离验证，记录见 `docs/refactor/13-phase-4a-shop-cache-governance.md`。
- Phase 4B：已完成搜索功能基线与本机隔离验证，记录见 `docs/refactor/14-phase-4b-shop-search.md`；选型见 ADR 0002，没有性能/相关性或目标版本验收结论。
- Phase 5：已完成实现与本机隔离验收，记录见 `docs/refactor/15-phase-5-engineering.md`；提交后审查完成才进入下一阶段。
- Phase 6A：已完成可观测性基线与本机隔离验证，244 项默认测试、82 项隔离 IT、10 组部署回归和 5 组实际 Prometheus 采集检查通过；见 `docs/refactor/16-phase-6a-observability.md`。按流程提交后审查完成再进入下一小阶段。
- Phase 6B：已完成固定标签业务诊断与核心合成 HTTP 故障补验；46 项定向、255 项默认（4 设计跳过）、82 项隔离 IT、8 组目标业务验收、10 组部署和 5 组管理面采集回归通过。范围与测量语义见 `docs/refactor/17-phase-6b-business-diagnostics.md`；按流程提交后复审。
- Phase 6C：选定只读范围的可复现原生 k6 基线完成：255 项默认回归（4 设计跳过）、23 项离线校验、原生参数拒绝检查、12 次正式测量/12 次预热及独立结果/清理复核通过，见 `docs/refactor/18-phase-6c-performance.md`。按流程提交后审查收口，不扩大为所有业务的性能验证。
- Phase 6D：补齐原始指定的三份面试材料，当前实现与历史阶段/目标设计分开表述；只改文档，验证与审查状态见 `docs/refactor/19-phase-6d-delivery.md`。
- Phase 6E1：交付限量活动负载工具与验收方案，当时未运行真实抢购，历史状态见 `docs/refactor/20-phase-6e1-flash-sale-tooling.md`。
- Phase 6E2：本机合成有限抢购批次及独立预热完成：20/200 账号各三轮、半库存、每人两请求，正式 1320 请求、330 新受理/330 重放/660 正常售罄；默认回归、54 项离线门禁、29 组 VM stub、真实账本、原始哈希/统计和清理复核通过，见 `docs/refactor/21-phase-6e2-flash-sale-results.md`。按流程提交后独立复审，不外推容量或逐单落库延迟。
- Phase 6F：当前 37 个显式应用 MVC 映射目录完成，两个管理端 GET 单列；新增 4 项契约检查，Java 8 默认回归/打包 259 项（4 设计跳过）通过。记录见 `docs/refactor/22-phase-6f-api-catalog.md`，按流程提交后独立复审；没有业务或运行配置变化。
- 实际性能数据：本机合成正缓存详情/MySQL 搜索的 1/10 VU 短时闭环基线见 `docs/performance/README.md`，限量活动有限批次及三类客户端耗时见 `docs/performance/flash-sale.md`；均保留原始汇总、参数、环境、哈希与逐轮统计。没有持续活动 QPS、订单 TPS、生产容量、优化提升或 SLO 结论；未覆盖范围保持显式待验。

## 5 原始目标的剩余范围

完成以上选定阶段不等于原始 Prompt 的全部目标完成。2026-10-10 重新对照原文后确认：

- 限量活动验证进展：6D 时尚缺的实际负载已由 6E1/6E2 补选定有限批次。合成身份/半库存、分类、唯一订单、收敛与资源限额均有实测；逐单落库延迟、持续到达率/容量、支付取消竞争与负载下故障恢复仍没有验证，不能把后台完成观察区间当逐单延迟。
- 文档覆盖进展：6F 已补当前显式应用路由的统一目录、两个管理端 GET 和漂移检查；不是完整 OpenAPI schema 或全部接口的真实依赖端到端验收。没有验收过的 UI 或系统截图，不虚构截图。
- 业务/结构演进：权限和商户成员基础已实现，不等于完整商户后台/管理后台；评论评价、通知、普通校园动态脱离门店关联、业务模块分包等尚未完整落地，需先决定最小用例和兼容范围。
- 保持暂缓：Java 17/Boot 3、专业 MQ、ES、Grafana、微服务/HA 不因“下一阶段”自动引入。历史迁移、真实短信及生产验收仍需独立范围。

6D 当时推荐的隔离抢购负载基线已按 6E1/6E2 分阶段补验，接口目录缺口按 6F 补当前源码范围；后续只从仍未交付/未验证事项中另定最小范围，不自动追加架构或产品功能。原始盘点的历史依据见 [Phase 6D](19-phase-6d-delivery.md)，当前活动负载边界见 [Phase 6E2](21-phase-6e2-flash-sale-results.md)，目录边界见 [Phase 6F](22-phase-6f-api-catalog.md)。
