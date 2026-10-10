# Phase 7B：一级评论发布与游标分页

日期：2026-10-10。应用基线：`3b4525bd4ca562c4b7bf84db33c9d0b02726f363`。在 7A 可选门店关联之后，本轮从空评论 Controller/Service 补齐“登录发布一级评论、按动态分页读取正常一级评论”的最小闭环；复用旧表，不同时展开回复、审核或通知。

## 当前接口范围

应用端目录从 37 条更新为 **39 条显式 method/path 映射、12 个 Controller**。独立管理端两个 GET 仍单独列出，不计入该数量。

| 方法 | 路径 | Handler | 方法级授权 |
| --- | --- | --- | --- |
| POST | `/blog-comments` | `createComment` | `hasAnyRole('USER', 'MERCHANT', 'ADMIN')` |
| GET | `/blog-comments/of/blog/{blogId}` | `listComments` | 无 `@PreAuthorize`；应用全局 SecurityConfig 仍要求认证 |

POST 请求仅接收 `blogId` 与 `content`：前者必填正数，后者非空白且最多 255 字符。未知字段会忽略，客户端提供的作者、主键、状态、父评论/回复标识、点赞数或计数不能写入。作者由当前会话取得；服务将 `parentId=0`、`answerId=0`、`liked=0`、`status=0` 写入评论，并在同一事务内锁定目标动态、插入评论、将动态 `comments` 以 `COALESCE(comments, 0) + 1` 增加。目标动态不存在为 404；SQL 异常对外为通用 500；插入或计数更新影响行数不符合预期时回滚并返回 422。

GET 要求正数 `blogId`，允许正数 `beforeId`；`size` 为 1..50，默认 20。按 `id DESC` 查询，游标条件为 `id < beforeId`。每次最多取 `size + 1` 行以判断是否还有下一页，只返回页面大小范围内的结果。查询过滤条件为 `status=0 AND parent_id=0 AND answer_id=0`，因此举报状态 1、隐藏状态 2、null 状态及回复均不返回。动态不存在为 404。

GET 成功响应的 `data` 是 `BlogCommentPage`：

- `items`：评论数组，每项只有字符串 `id`、`blogId`、`userId`、`content`、`createTime`。
- `hasNext`：是否发现额外一行。
- `nextBeforeId`：当 `hasNext=true` 时取本页最后一条评论的字符串 ID，下一次请求用它作为 `beforeId`；没有下一页时为 null，当前 NON_NULL 配置下通常省略。

创建成功返回的 `BlogCommentItem` 使用同一组字段和字符串 ID。没有向外暴露 `parentId`、`answerId`、`liked` 或 `status`。

BlogComments.status 从 Boolean 改为 Integer，保留数据库的 0/1/2/NULL 语义。列表的存在性与读取使用只读 REPEATABLE_READ 事务；单请求一致不代表跨页快照。内容作为不可信纯文本保存，展示方须转义；无请求幂等，响应丢失后不能盲目重试并宣称不会重复。原始动态 comments 是历史聚合值，不保证等于可见一级评论数。

## 数据库迁移和部署

V007 `V007__add_blog_comment_page_index.sql` 为 `tb_blog_comments` 增加索引 `(blog_id, parent_id, answer_id, status, id)`。fresh bootstrap 顺序现在为 `schema.sql`、V001…V007、seed；已存在的数据卷不会因应用重启自动执行新脚本。

升级已有库前必须先备份并人工审查结构，评估 DDL 锁表影响，再先应用 V007、后升级应用。迁移仅按 `idx_blog_comments_page` 索引名判断是否存在；同名但定义错误时不会自动修复。此迁移不回填历史动态评论计数、不改写/删除历史评论，也不代表任意旧库结构已经验证。

## 明确不包含

本阶段范围不含回复、删除、审核或状态变更、通知、请求幂等键、历史 `comments` 计数回填、完整评论评价系统或生产行为声明。目录契约断言只检查注册映射、Handler 和声明的 `@PreAuthorize`；它不代替安全过滤链、真实 MySQL 行为或部署验收。

## 本轮实际验证

- Corretto 1.8.0_492 / Maven 3.9.9，项目专用离线依赖缓存，clean package 成功：**294 项默认测试，0 失败/错误、4 项教学实验按设计跳过**。相对 7A 新增 19 个案例：4 项评论 HTTP 模型、3 项安全链、12 项服务测试；原目录契约的空 Controller 断言替换为两条新映射与授权断言。
- **8 项显式 BlogCommentsMySqlIT** 通过，本机 MySQL 9.6.0，私有临时进程/随机 localhost 端口，生产 fresh schema、V006/V007 重复执行，真实 MyBatis-Plus 和 Spring 事务代理。覆盖 NULL 旧计数、插入/计数 SQL 异常及零行更新的真实回滚、12 个并发作者不丢计数、0/1/2/NULL 状态映射、一级可见过滤、不同动态隔离、游标翻页中插入、缺失资源/会话/非法输入及超过安全整数的 ID。显式 IT 不包含 Redis 或真实安全过滤链。
- 第一次定向编译暴露测试中 ArrayNode.stream() 与当前 Jackson 不兼容，改成已有 foreach 模式；随后测试复现两类夹具问题：DriverManagerDataSource 跨连接 LAST_INSERT_ID 得 0，以及 6 线程等待 12 个任务 ready 的启动屏障。分别改为同次 INSERT 的生成主键返回、12 个参与线程，真实失败/通过记录均保留在私有临时日志。两处 Mockito Long 默认 0 也改为明确 null，以正确模拟查无动态，不改业务缺失语义。
- 首次默认回归的 6 个错误均为既有 HTTP/健康测试监听本地端口被沙箱拒绝；获得所需权限后同一命令原样通过，未绕过这些测试或改配置。

- **13 组真实 Compose 检查**通过，MySQL 8.4.11 / Redis 6.2.24，应用为本轮 Java 8 jar。新增评论组覆盖真实 USER/MERCHANT/ADMIN、匿名和停用账号拒绝、实体字段白名单、非法参数/缺失动态、三态/NULL/回复过滤、超过安全整数的字符串 ID、分页间新增和 NULL 旧计数；故障组在私有库注入插入/计数触发器异常，实际 HTTP 返回通用 500 且两表无部分写入。原有发布/Feed、图片、初始化/V001–V007 重复、重启、依赖故障、主机 jar 和 prod 缺配置拒绝也重跑。
- 自建 MySQL IT 进程退出；唯一 Compose 项目的容器、网络、合成卷及本地应用镜像由 finally 清理，之后四类资源分别只读复核均无残留。基础镜像保留，未清理其他项目或用户资源。

- 7 份变更 Markdown 的 183 个相对文件链接、代码围栏、39 行接口表列宽/机器目录、bootstrap shell/Python 语法、git diff --check 与变更文件定向私人路径/密钥模式检查通过。模式检查不是全历史密钥审计，链接存在性不证明行锚点语义；表格检查识别转义竖线，不把既有 jpg\|png 单元格误判为额外列。

没有将历史 82 项隔离 IT、7A 独立发布 IT、6B 诊断/Prometheus 或 6E2 负载当成本轮重跑。

## 复现与边界

```bash
./mvnw clean package
./mvnw -Dtest=BlogCommentsMySqlIT test
python3 scripts/verify-compose.py
```

需要 Java 8、mysqld 与本地 Docker，见 README。Surefire 默认不发现 *IT，不能省略显式命令；测试只能自建进程/项目和合成数据，不能连接共享 DB/Redis。临时诊断日志不入 Git。

按阶段流程验证后提交，再由独立 Luna 作实际提交后复审，通过才普通推送并读回远端；最终审查/同步结果在交付对话报告，不提前记作成功。阶段结束后停下，不自动进入下一用例。

## 相关文档

- [当前 API 目录](../api/README.md)与[逐接口说明](../api/endpoints.md)
- [一级评论与游标分页学习笔记](../learning/first-level-comments-and-cursors.md)
- [V007 索引迁移](../../src/main/resources/db/migration/V007__add_blog_comment_page_index.sql)
