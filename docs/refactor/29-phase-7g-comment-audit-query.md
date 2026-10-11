# Phase 7G：单条隐藏审计查询

日期：2026-10-11。前置提交为 `a825845ce2197b5c77d06139c4c7639ec04e3732`，从干净 main 开始；远端 main 本轮只读核验同此基线。原教学仓库和要求文档未修改。

本阶段为 7F 已有的隐藏审计记录增加 ADMIN-only 单条读取接口。它复用 V008，不增加 schema migration。目录累计 45 个应用显式 method/path 映射、13 个 Controller；评论相关路由为原 6 条和 2 条管理路由（隐藏、查询）。

## 接口与边界

```http
GET /admin/blog-comments/{commentId}/hide-audit
authorization: <session-token>
```

Controller 为 [`AdminBlogCommentsController`](../../src/main/java/io/github/frewily/campushub/controller/AdminBlogCommentsController.java)，handler `queryHideAudit(Long)` 使用 `hasRole('ADMIN')`。Path ID 必须是正 Java Long：非法、非正或溢出值 400。匿名、停用账号和未知/无效角色身份 401；有效 USER/MERCHANT 为 403，即使该账号是评论作者或审计操作人也不例外。只有 ADMIN 能查询。

成功响应为 `Result.ok(dto)`。DTO 是 [`BlogCommentHideAuditResponse`](../../src/main/java/io/github/frewily/campushub/dto/response/BlogCommentHideAuditResponse.java)，仅含以下八个字段：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `id` | `String` | 审计记录 ID |
| `commentId` | `String` | 评论 ID |
| `blogId` | `String` | 动态 ID |
| `operatorUserId` | `String` | 执行隐藏的账号 ID |
| `reason` | `String` | 首次隐藏原因 |
| `fromStatus` | `Integer` | 转换前状态 |
| `toStatus` | `Integer` | 转换后状态 |
| `createTime` | `LocalDateTime` | 审计记录时间 |

时间序列化为不带时区偏移的 ISO 本地日期时间；默认 dev/Compose JDBC 会话固定 UTC，prod 的解释取决于部署的 DB_URL 时区设置。本阶段不改全局时间契约，不宣称任意部署都返回 UTC。

没有对应审计行时返回 404；数据库访问异常映射为不含底层异常细节的通用 500。实现分别见 [`BlogCommentsServiceImpl`](../../src/main/java/io/github/frewily/campushub/service/impl/BlogCommentsServiceImpl.java)、[`BlogCommentsMapper`](../../src/main/java/io/github/frewily/campushub/mapper/BlogCommentsMapper.java) 和 [Mapper SQL](../../src/main/resources/mapper/BlogCommentsMapper.xml)。

## 查询与快照语义

读取只使用参数绑定的普通 `SELECT`，设置 5 秒查询超时，运行于只读 `REPEATABLE READ` 事务；不使用 `FOR UPDATE`。查询从 V008 审计表独立取回已记录的操作元数据，不 join 当前评论、动态或账号表，也不通过当前评论状态推断历史隐藏事实。审计行缺失即 404，即使当前评论状态为 2 也不会推测或补造记录。

事务快照只包含已提交且对该快照可见的数据，不读脏数据或别的事务尚未提交的 INSERT。查询在一个此前已建立快照的 REPEATABLE READ 事务内执行时，可能继续看到旧快照；这不表示数据库没有后来提交的记录。V008 本身不会回填旧的隐藏状态，审计表也不是防篡改日志。

接口只读，不修改评论状态、动态计数或审计记录。范围不含新迁移、审计列表、导出、恢复、举报流程、审核 UI 或完整管理后台。它是对 7F 审计元数据的一项窄读取能力，不代表完整后台已交付。

V008 须已具备（已有部署按 7F 升级说明处理）；本阶段无 V009、历史回填或共享库 DDL 操作。回退应用可保留审核数据，不删除表或恢复评论状态/计数。普通读避免隐藏事务的行锁等待，但不承诺抵御 DDL 元数据锁或任意数据库延迟；5 秒是 JDBC 查询超时配置，不是端到端 SLA。

## 验证状态

- **26 项定向测试**通过，0 失败/错误：新查询服务 4 项、HTTP 5 项，既有隐藏服务 8 项、HTTP 5 项、源码目录 4 项。覆盖八字段白名单、四个大 ID 字符串、时间序列化、各角色/原操作人、停用/无角色/未知角色、路径边界、404/500、异常无 cause、单 Mapper 调用及成功/拒绝请求后的身份清理。
- **Java 8 clean package 360 项默认统计**，0 失败/错误、4 项原有教学实验设计跳过（7F 的 351 加本阶段 9）。Corretto 1.8.0_492 / Maven 3.9.9，专用离线缓存；保留原评论/回复/撤回及安全回归。默认 Surefire 不发现 *IT，本结果不能代替真实数据库验收。
- **43 项显式 BlogCommentsMySqlIT**通过（既有 38 加本阶段 5），本机 MySQL 9.6.0，自建临时 mysqld/随机 localhost 端口、真实 MyBatis XML/Spring 事务代理。新增四个 BIGINT 到字符串无损映射、真实隐藏后首个元数据/时间与重复读取不变、旧隐藏/作者撤回/缺失行不推造记录、删除合成原评论/动态/操作人后仍保留查询。拦截器断言查询实际事务为 read-only/RR。真实审核 INSERT 后、隐藏事务提交前，独立线程查询及时完成并返回 404；提交后新事务查到记录。另一场景在真实 INSERT 后测试专用返回 0 行，触发生产 OPERATION_FAILED 守卫整体回滚，期间及回滚后的独立读取均 404，原状态/计数保留。不是把直接写入一条审计行冒充完整隐藏链路。
- 系统化调试保留失败事实：首次完整 IT 为 42 通过、1 项时间断言失败；单项重现八小时偏差，不改查询代码、只切换 JVM 到 UTC 后单项通过。根因为测试 Timestamp.toLocalDateTime 隐式使用 JVM Asia/Shanghai，而测试 JDBC 会话/MyBatis LocalDateTime 为 UTC；仅将测试比较显式归一 UTC，恢复本机时区后完整 43 项通过。辅助调试文件/关联技能未提供，按已读取的主技能完成根因、对照和回归；没有为使测试通过修改业务时间语义。
- 完整默认构建首次在受限沙箱中为 360 项、6 个本机端口绑定错误、4 跳过（无断言失败）；堆栈定位为健康/可观测性测试 bind 的 Operation not permitted。源码不改，只以所需本机监听权限重跑后通过，未跳过这 6 项。
- **23 组目标 Compose 验收**通过（既有 21 加本阶段 2），MySQL 8.4.11 / Redis 6.2.24，使用本轮 Java 8 jar和合成账号。真实 HTTP/会话/SQL验证管理员查询、普通作者/商户 403、停用/无角色 401；原操作人降级 USER 后 403，其他管理员及仍为管理员的原操作人可查询。完整八字段、四个大编号字符串、数据库时间、重复隐藏保留首次记录/读取不改计数状态；无审计旧行/撤回行/缺失行 404，移除专属合成原始行后元数据仍在。原部署、内容、审核故障、图片、重启、依赖故障、主机 jar/prod 配置回归实际重跑；私有 result.json 读回为 23 组和上述版本。
- 唯一自建项目 finally 清理；独立只读确认容器、网络、合成卷、该项目应用镜像均无残留，测试进程退出。只移除可重新生成的测试资源，保留基础镜像/其他项目；原教学仓库的未跟踪文件保持原样。日志及私有 result.json 不提交。

7F 的历史数量/证据不改写。本阶段未重跑其他领域 IT、性能原始实验或生产负载，没有新增吞吐、延迟、容量或 SLA 数字；不等同于全部 45 个接口完整端到端验收。

收尾定向检查通过：19 个变更文件中私人路径/密钥模式无匹配，7 份 Markdown 围栏及 238 个相对文件链接存在性、45 行应用 API 表格列宽/机器清单（13 个 Controller、6+2 条评论路由）、两个独立 Actuator GET、Python 语法与 git diff --check。目录文档收尾后 4 项源码映射契约重跑通过。不是全历史密钥审计、行锚点语义验证或所有端点端到端验收；私人诊断文件不进入提交。

## 复现与阶段出口

```bash
./mvnw clean package
./mvnw -Dtest=BlogCommentsMySqlIT test
python3 scripts/verify-compose.py
```

要求 Java 8、mysqld、本地 Docker及本机监听测试权限，仅允许自建隔离数据库/Compose和合成身份。原始私有配置/共享服务不在范围内。

验证后范围清晰提交，由未参与实现的 Luna 对实际 SHA 独立审查，通过后普通推送并读回远端；最终 SHA、审查和推送状态在交付对话报告，不在此提前宣称。完成本阶段即停下，不自动增加列表、恢复、举报或通知。

接口目录见[逐接口说明](../api/endpoints.md)，设计解释见[学习笔记](../learning/comment-hide-audit-query.md)。
