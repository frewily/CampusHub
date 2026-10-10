# Phase 6D：面试材料交付与原始目标收口盘点

日期：2026-10-10。实现基线：`4882a20348ed3d2e7b87a6fe761bb7da2ebf36ae`。

## 1. 为什么增加这一小阶段

原项目 `docx/` 目录保存的参考文件实际是《黑马点评 → CampusHub 企业级项目重构 Prompt.md》，不是 Word 二进制。重新完整阅读后确认，原文除分阶段工程改造外，明确要求三份独立面试文件，并把抢购接口列为重点压测对象。

6C 已交付只读基线及面试 README，但三份指定文件尚未独立交付，抢购负载也没有实测。本阶段只补文档、解释当前实现并盘点剩余范围；不把性能基线的小范围出口当成原始需求的全部出口。原项目只读，参考文档不在本阶段复制或改写。

## 2. 范围与不做事项

新增：

- `docs/interview/project-story.md`：项目来源、演进路径、四条调用链、设计取舍、验证层次与学习接管。
- `docs/interview/questions.md`：源码/测试可追踪的面试追问，先脱稿再核对。
- `docs/interview/resume-points.md`：按本人实际理解选择的候选表述，不冒充个人已掌握或独立实现。
- 本文：原始要求与当前交付边界。

更新 interview 索引、README 和迁移计划。没有 Java/Lua/schema、依赖、接口或部署行为变更；不新建后台/UI、升级 JDK/框架、接入短信、引入 MQ/ES 或进行写入负载。默认回归只复核现有基线，不宣称新业务实现。

## 3. 原始要求对照

下表是本轮源码与文档核对，不是本轮对所有能力重新做运行验收。

| 原始要求 | 当前状态与证据 | 不能据此宣称 |
| --- | --- | --- |
| 分阶段审计、稳定化与去教学身份 | Phase 0–2D 记录、根包 `io.github.frewily.campushub`、Wrapper 和错误/请求模型 | 旧领域名全部替换、所有 Controller 全部隔离 Entity |
| 认证、USER/MERCHANT/ADMIN、资源归属 | Spring Security、可撤销 Redis 会话、DB 状态/角色、商户成员与门店归属；[2C](08-phase-2c-authorization.md)、[6B](17-phase-6b-business-diagnostics.md) | JWT/独立 refresh token、真实短信、完整商户后台或角色管理产品 |
| 活动、异步订单、幂等与失败恢复 | Lua、Stream pending claim、有界重试/DLQ、DB 唯一约束、本人订单查询/取消及 outbox；[3A](10-phase-3a-flash-sale-admission.md)、[3B](11-phase-3b-reliable-order-consumption.md)、[3C](12-phase-3c-order-lifecycle.md) | exactly-once、支付/退款/核销/自动超时取消、学生认证、活动高负载容量 |
| 消息与搜索选型 | [ADR 0001](../adr/0001-order-message-broker.md) 保留 Stream；[ADR 0002](../adr/0002-shop-search-engine.md) 采用 MySQL 搜索 | 已接入 RabbitMQ/RocketMQ/ES，或这些组件永远没有价值 |
| 缓存治理、搜索条件与同步 | [4A](13-phase-4a-shop-cache-governance.md) 的 Cache Aside/栅栏/outbox；[4B](14-phase-4b-shop-search.md) 的 MySQL 筛选/排序/快照分页，无 ES 双写 | 强一致、缓存优化提升比例、中文全文相关性、全部 Redis 投影可重建 |
| 可运行部署、配置、测试与指标 | [5](15-phase-5-engineering.md)、[6A](16-phase-6a-observability.md)、[6B](17-phase-6b-business-diagnostics.md) 记录本机合成隔离验收 | 历史迁移安全、生产就绪、多副本/HA、关键窗口强杀已验收 |
| 商户查询、热点缓存、抢购压测 | [6C](18-phase-6c-performance.md) 与[性能报告](../performance/README.md) 已有两类只读基线；抢购实际压测仍未执行 | 用读取 RPS 代替活动 TPS、生产容量、万人并发、性能提升或 SLO |
| 三份面试材料、学习解释 | 本阶段补齐三份文件；既有 `docs/learning/` 按设计记录取舍与追问 | 本人已独立实现/已掌握，或文档本身就是运行证据 |
| README 的 API 与截图 | 有重点接口契约和运行方法；无统一穷尽 API 目录、无已验收 UI/系统截图 | 完整 OpenAPI 已交付、截图/前端已实现 |
| 校园业务、后台、评价/通知与结构演进 | 当前分层单体，复用门店、内容/关注/Feed、签到、活动；商户成员权限基础已落地 | 完整校园平台、所有目标业务模块分包、评论评价/通知/后台已交付 |

JWT、专业 MQ、ES、Grafana 和微服务的暂缓有现有取舍或原文“非全部必加”的依据，不把技术名称缺席本身算成漏洞。抢购压测则是明确未完成项，不能通过“合理裁剪”从要求中抹掉。未交付的产品用例要先定边界，不在文档阶段顺带实现。

## 4. 历史快照与当前事实

- `docs/domain-model.md` 是 Phase 1A 设计快照，其中 Merchant/MerchantMember “不存在/后续增加”不是当前事实；V002 已创建 `tb_merchant`、`tb_merchant_member` 并增加门店归属，`AccountAccessMapper` 与资源授权代码已使用它们。完整后台仍未交付。
- 2C 当时未做真实 MySQL V002 验证，后续 Phase 5 fresh 初始化/重复迁移和 6B 目标版本合成权限链提供了选定范围的补验；不能反向改写历史记录成当时已验证，也不能据此外推历史脏库迁移安全。
- 目标架构中的业务模块包是最终方向，当前仍主要是技术层分包。原本的概念图、枚举和状态设计不自动代表对应接口存在。
- 旧阶段“尚未压测”描述当时边界；6C 仅更新只读范围，不改变抢购负载、SIGKILL、HA 等未验项。

## 5. 本轮验证与审查

文档和本轮验证已完成；本提交按流程进行独立提交后审查，通过后才推送，不进入下一个实施阶段。

- Corretto `1.8.0_492`、Maven `3.9.9` 的离线 `clean package` 成功。255 项默认测试，0 失败、0 错误，4 项教学实验按设计跳过；打包 jar 存在，Surefire XML 汇总复核一致。
- 初次尝试默认离线缓存，在编译前因缺少 `maven-enforcer-plugin:3.5.0` 失败。依照系统化调试核对已有项目专用缓存与 central 元数据后，显式选择该缓存重跑成功；不改 POM、不升级依赖，也不把初次失败算成测试通过。默认套件的随机本机 HTTP 监听器在允许本机监听的环境运行，不连接共享 DB/Redis。
- 七份变更文档的 141 个本地链接和 21 个不同测试类引用均存在；代码围栏闭合，简历候选的 RPS/P95 数字与历史 manifest 一致。
- 差异复核确认仅七份文档变更，Java/Lua/schema、配置、脚本、部署和性能原始证据均未修改。`git diff --check` 与本轮文档的定向凭据/私人路径检查通过；该检查不是通用密钥扫描或全历史安全审计。

默认复现入口为仓库 README 中的 `./mvnw clean package`（Java 8）；离线运行要求事先完整缓存，项目缓存位置由运行者显式配置。本文不提交构建日志、机器私人路径、临时检查脚本或任何个人环境文件。

本轮没有重跑显式 DB/Redis IT、Compose 故障验收或 k6 测量；既有 82 项 IT、8/10/5 组验收及 12 轮读取测量均是对应历史阶段证据，不计为本轮新增结果。

## 6. 下一阶段建议（本阶段不执行）

优先补原文明确要求的限量活动隔离负载基线。开始前约定合成账号/库存、1 人 1 活动的身份模型、受理/重放/售罄等拒绝分类、HTTP 受理耗时与异步落库延迟、稳态/恢复等待和资源上限；检查 Redis/DB 库存与唯一订单，并只清理测试自己创建的资源。不能用所有 HTTP 200 或 handler 计数当作成功订单数。

统一 API 目录或产品业务深化也可以作为后续选项，但需另定一个最小用例及兼容计划。不会默认切换到前端开发、Boot 3、专业 MQ、ES、多副本或生产 HA。
