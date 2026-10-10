# CampusHub 简历要点：使用前先确认本人掌握

实现基线：`4882a20`。以下是可选的事实表述，不是已确认的个人能力或工作经历；项目由教学工程演进、AI 辅助重构。只有亲自阅读、复现并能回答对应追问的部分才能使用，未掌握时写“正在接管学习”，不要把提交作者或测试数量当独立实现证明。

## 项目介绍候选

CampusHub：从黑马点评教学工程渐进演进的校园生活与周边商户服务后端。在 AI 辅助下围绕权限、活动受理、可靠消费、订单取消与缓存一致性建立可验证的故障恢复路径，补充本地部署、诊断指标和只读性能基线。

不使用“独立从零开发”“真实商业上线”“企业级高可用”或未经确认的用户规模。商户/管理员权限不是完整产品后台，校园定位不等于已交付学生认证、评论评价和通知体系。

## 按实际理解选择两到三个要点

| 候选表述 | 使用前需要能解释/复现 | 证据 |
| --- | --- | --- |
| 基于 Spring Security 与可撤销 Redis 会话实现角色和门店资源归属校验，为优先写接口增加请求字段白名单与统一错误语义 | opaque token、禁用/登出、有效商户成员、越权拒绝；不是 JWT/refresh token，也不是所有遗留接口均已隔离 Entity | [权限](../refactor/08-phase-2c-authorization.md)、[API 边界](../refactor/09-phase-2d-api-models.md) |
| 以 Redis Lua 完成限量活动准入与重复受理，结合 Redis Stream pending 接管、有界重试、失败归档和数据库唯一约束处理异步建单 | ACCEPTED 不等于已落库；提交后 ACK 失败、同 ID 幂等、冲突 ID、失败库存保留与人工核对后的 redrive | [准入](../refactor/10-phase-3a-flash-sale-admission.md)、[可靠消费](../refactor/11-phase-3b-reliable-order-consumption.md)、[ADR 0001](../adr/0001-order-message-broker.md) |
| 用 MySQL 事务 outbox 与独立 Redis 幂等 marker 支持本人未支付订单取消及补偿恢复 | DB/Redis 分别确认、租约/CAS、Redis 成功但 DB 确认失败、PENDING 转核对；未实现支付/退款 | [订单闭环](../refactor/12-phase-3c-order-lifecycle.md)、[取消笔记](../learning/order-cancellation-recovery.md) |
| 门店详情采用 Cache Aside、负缓存、TTL 抖动、有界 token 互斥与 epoch 发布栅栏，通过事务 outbox 恢复提交后的失效 | 旧 loader 回填交错、generation 与 epoch 的区别、竞争预算、503 边界；不宣称强一致或优化提升 | [缓存治理](../refactor/13-phase-4a-shop-cache-governance.md)、[缓存笔记](../learning/shop-cache-consistency.md) |
| 建立隔离 Compose 验收与固定标签业务诊断，覆盖合成登录/权限、Feed、缓存失效、订单重放和取消确认失败 | 默认 mock、本机 IT 与目标版本 HTTP 证据区别；计数不是唯一订单数/lag；优雅重启不是强杀或 HA | [部署](../refactor/15-phase-5-engineering.md)、[诊断验收](../refactor/17-phase-6b-business-diagnostics.md)、[测量语义](../learning/business-diagnostics.md) |

MySQL 搜索可以作为取舍补充：实现名称子串、类别/价格/评分/距离筛选与固定排序分页，当前不引入 ES，见 [ADR 0002](../adr/0002-shop-search-engine.md)。不写“通过 ES 实现搜索”“搜索索引实时同步”或无执行计划/前后对照支撑的索引收益。

## 性能数字：可选，不是必需

若本人已复现并能解释测量模型，可使用这样的带条件表述：

“在 Apple M5 单机、应用容器 2 CPU/768 MiB、1000 条合成门店的原生 k6 闭环只读基线中，正缓存详情 10 VU 的三轮 RPS 中位数为 7241.88，各轮 P95 的中位数为 1.577 ms；每轮独立 5 秒预热/20 秒测量。不是生产容量或优化前后对照。”

完整环境、三轮范围、P99 与原始汇总见 [性能报告](../performance/README.md)、[manifest](../performance/results/20261009T144219Z/manifest.json)。应用容器 JDK 为 Temurin 8，宿主构建 JDK 为 Corretto 8，不要混写。数字是相同用例三轮逐轮指标的中位数，不是合并请求分布的百分位数；VU 不等于在线用户，两种接口不能比较成缓存收益。

若简历版面容不下条件，优先写“建立可复现的只读基线并保存原始结果”，不孤立使用“支持 7000+ QPS”。尚未进行抢购实际压测，也没有 TPS、生产成功率、提升百分比、万人并发或 SLO 结论。

## 使用前自检

- 能脱稿讲清一条完整调用链和至少一个失败窗口，而不是只列技术栈。
- 能指出自己实际读过、改过或复现过什么；AI 辅助与未掌握部分如实说明。
- 能找到原始验证入口与阶段记录，并区分本次复现、历史证据和未验证边界。
- 不把设计文档里的模块、状态或未来业务写成当前能力；不把测试通过当个人掌握。

学习入口：[项目故事](project-story.md)、[面试追问](questions.md)。原始交付盘点与仍需另定范围的事项见 [Phase 6D](../refactor/19-phase-6d-delivery.md)。
