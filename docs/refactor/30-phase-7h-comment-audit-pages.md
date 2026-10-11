# Phase 7H：按动态分页读取评论隐藏审计

日期：2026-10-11。前置提交为 `3d1cbc47c794a8a2d4088604119f5ac1473654ae`，从干净 main 开始；远端 main 本轮只读核验同此基线。原教学仓库与要求文档未修改。

状态：接口实现、V009 和选定本轮验证完成。提交后由未参与实现的 Luna 独立审查，通过后普通推送并读回远端；最终 SHA、审查与推送状态在交付对话报告，不在此提前宣称。

## 目标与范围

为 ADMIN 增加按单个动态读取隐藏审计记录的只读游标页接口。复用 7F 写入的 V008 审计数据，不读取当前评论、动态或操作人来补充结果。

- 新路由：`GET /admin/blog-comments/hide-audits/of/blog/{blogId}`，handler `listHideAudits`，`@PreAuthorize("hasRole('ADMIN')")`。它使用业务应用端口，不属于 Actuator 管理监听器。
- `blogId` path 必须是正 Java Long。可选 `beforeId` 是正 Java Long 审计记录 ID，不是评论 ID；使用严格小于游标的下一页语义。`size` 默认为 20，允许 1..50。未知 query 参数忽略。
- 匿名、停用账号、无有效角色或未知角色身份仍为 401；已认证 USER/MERCHANT 为 403；参数无效或 Long 溢出为 400。ADMIN 可读取任意动态的审计页，不要求作者、评论操作人或当前动态/账号仍存在。
- 空页、无审计记录、缺失当前 blog 都返回 200：`items: []`、`hasNext: false`，`nextBeforeId` 为 null 并按 NON_NULL 配置省略。不查当前 blog 是否存在，也不做 blog join。

## 查询与分页响应

每次调用在只读 REPEATABLE READ 事务执行一个普通、参数化、绑定参数的单页 SELECT，查询超时 5 秒。条件为 `blog_id = ?`，有游标时附加 `id < ?`，按 `id DESC` 排序并使用 `LIMIT size + 1`。多取的一行仅用于计算 `hasNext`，返回前裁掉；不执行 COUNT 或第二次查询。

返回 `Result.ok(page)`，page 包含 `items`、`hasNext`、`nextBeforeId`。每个 item 复用既有八字段白名单：`id`、`commentId`、`blogId`、`operatorUserId` 为字符串；`reason` 为字符串；`fromStatus`、`toStatus` 为 Integer；`createTime` 为 LocalDateTime。时间不带 offset；dev/Compose 默认 UTC，prod 解释受 `DB_URL` 配置影响，不能当作带时区时间戳。

当 `hasNext=true`，`nextBeforeId` 取本页最后一条已返回 item 的审计 ID，十进制字符串表示；客户端下一次将其作为 `beforeId`。没有下一页时令其为 null，由 NON_NULL 设置省略。ID 字符串避免客户端对 Java Long 精度丢失。

## 一页快照不等于跨页冻结

每个 page 请求各自建立 RR 事务。事务只保证本页查询使用自己的数据库快照；分页游标不创建或保存跨请求快照。自增审计 ID 代表分配顺序，不保证提交顺序。新记录通常排在第一页前部，但一个较小 ID 的事务可能较晚提交，在客户端越过它所在的游标之后才变为可见，因而跨页结果可能漏过该记录。接口不保证跨页无遗漏、完整一致快照或固定 watermark。

不根据 ID 顺序宣称审计时间顺序或提交顺序，不给出延迟、吞吐、规模或性能 SLA。单页的 `hasNext` 只反映该次查询快照中是否多读到一行。

## V009 与部署顺序

候选迁移文件名：`V009__add_blog_comment_hide_audit_page_index.sql`。它为 V008 的 `tb_blog_comment_hide_audit` 增加候选复合索引 `idx_blog_comment_hide_audit_page (blog_id, id)`，支持按动态过滤并按主键倒序游标读取。

- V008 审计表必须已存在，之后才能应用 V009。
- 迁移以索引名检查后通过 PREPARE/执行路径跳过重复创建；同名但列或顺序定义错误的既有索引不会自动修复，也不应被文档视为有效。
- fresh schema 顺序应为 `schema.sql → V001…V009 → seed.sql`。这只是新卷 bootstrap 顺序。
- 对已有卷或共享数据库，先备份，再人工核对 V008 表及索引定义，审查 V009 后由维护者显式应用；不自动运行共享 DDL。应用升级前确认 V009 已成功应用。
- 回退旧应用可以保留该索引。不自动 DROP 索引、不改评论状态、不恢复动态计数、不删除审计记录。

首次/重复执行、缺表失败、同名错误形状不修复及行保留已在隔离 MySQL 验证。未测生产 MySQL DDL 锁/耗时、执行计划/优化器选择或性能改善；5 秒为 JDBC 查询超时配置，不是端到端 SLA。

## 文档目录与历史记录

当前 API 清单为 46 条显式应用 method/path 映射、13 个 Controller；评论相关映射为原有 6 条评论/回复/撤回路由，加 3 条 ADMIN 隐藏与审计路由。Actuator 两条 GET 仍单独列出，不计入应用路由。机器清单 `source_revision` 继续表示目录初建基线。

本阶段同步 README、API 清单/逐接口表、领域模型、迁移计划与可复现部署说明。7F 和 7G 阶段历史计数、测试边界及最终交付说明保留原样；它们不是本阶段证据。

## 不在本阶段范围

不提供按 blog 以外条件过滤、全局审计列表、COUNT/总量、导出、恢复/修复、状态或计数写入、操作人/评论/动态 join、审核 UI、完整后台、历史审计回填或跨页快照令牌。无权限角色不能通过其他接口绕过本路由权限。任何实现若需扩大上述范围，应单独定义阶段和兼容策略。

## 验证证据与未验证范围

- **40 项定向检查**通过，0 失败/错误/跳过：新分页服务 7 项、HTTP 6 项、迁移静态 1 项，既有 7G 查询服务/HTTP 4/5 项、7F 隐藏服务/HTTP 8/5 项，源码 API 目录 4 项。覆盖默认/最大 size、lookahead 裁剪及最后返回审计 ID 游标、正数/溢出/非法参数、身份/角色、八字段白名单、大 ID 字符串、时间与空页序列化、单 Mapper 调用和通用 500 无 cause。HTTP 契约测试使用真实安全过滤链/方法授权、模拟 Service/身份存储，不冒充真实数据库。
- **Java 8 clean package 374 项默认统计**通过，0 失败/错误、4 项原有教学实验设计跳过；Corretto 1.8.0_492 / Maven 3.9.9，专用离线依赖缓存及所需本机监听权限。默认 Surefire 不发现 `*IT`，不能以此代替显式数据库验收。
- **49 项显式 BlogCommentsMySqlIT**通过（既有 43 项加本阶段 6 项），独立临时 MySQL 9.6.0/随机 localhost 端口，使用真实 MyBatis XML 与 Spring 事务代理。新增验证：V009 重复执行保留已有真实隐藏审计、列定义为 blog_id/id；私有空表库缺 V008 时失败；临时同名 `(id)` 索引不被 V009 自动修复，最终恢复正确形状。多动态混合、隐藏顺序不同于评论编号、大 ID、严格游标、空页、精确页长和重复读取不写入均验证；专属合成原始评论/动态/操作人删除后审计仍可读，旧隐藏无审计不推造记录。拦截器断言实际查询事务为 read-only/RR。真实隐藏 INSERT 之后而提交之前，独立线程分页及时完成且为空；提交后的新事务可见记录。另一场景真实 INSERT 后测试专用返回 0 行触发生产守卫整体回滚，期间/之后独立分页均为空，原状态/计数保留。50+1 上限用 **51 条合成导入审计元数据**，不是 51 次应用隐藏。
- **25 组目标 Compose 验收**通过（既有 23 组加本阶段 2 组），MySQL 8.4.11 / Redis 6.2.24，使用本轮 Java 8 jar 与合成身份。真实 HTTP/会话/SQL 覆盖 ADMIN-only、当前角色/原操作人降级、非法 path/query、未知字段、按动态隔离、完整白名单、大 ID、审核而非评论 ID 游标、默认 20/最大 50 与下一页、空/缺失动态 200、读取不写入、移除专属合成原行后保留记录及 V009 重复保留元数据。最大页同样用合成导入元数据，不冒充应用审核链。原部署、内容、审核故障、图片、重启、依赖故障、主机 Java 8 jar/prod 配置回归实际重跑；私有 result.json 独立读回为 25 组及上述版本。
- 唯一自建 Compose 项目 finally 清理，独立只读确认其容器、网络、合成卷和应用镜像无残留；只移除可重新生成的测试资源，保留基础镜像/其他项目。构建、IT 与验收进程退出；原教学仓库预存未跟踪文件保持原样，日志与私有结果不提交。
- 收尾检查覆盖本阶段 24 个变更文件的私人路径/密钥模式、8 份 Markdown 围栏与相对文件链接存在性、46 行应用 API 表格列宽/机器清单（13 个 Controller、6+3 评论路由）、两个单独 Actuator GET、Python AST、bootstrap shell 语法及 git diff --check；文档收尾后重跑定向检查。不是全历史密钥审计、链接行锚点语义核验或所有 46 个端点完整端到端验收。

测试汇合时核对并统一了索引名预期 `idx_blog_comment_hide_audit_page`，没有为迁就测试修改业务索引。7F/7G 历史记录不改写；本阶段未重跑其他领域 IT、6E2 性能原始实验或生产负载，没有新增吞吐/延迟/容量数字。

## 复现与阶段出口

```bash
./mvnw clean package
./mvnw -Dtest=BlogCommentsMySqlIT test
python3 scripts/verify-compose.py
```

要求 Java 8、mysqld、本地 Docker 及本机监听权限，仅允许自建隔离数据库/Compose 和合成身份，不读取个人 .env/凭据或使用共享服务。

范围清晰提交后，由未参与实现的 Luna 对实际提交 SHA 独立静态复审。审查通过再普通推送并读回远端；完成本阶段即停下，不自动增加全局列表、导出、恢复、举报、通知或 UI。
