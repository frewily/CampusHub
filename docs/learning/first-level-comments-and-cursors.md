# 一级评论：事务计数与 ID 游标

Phase 7B 只交付“登录后发布一级评论、读取一条动态的正常一级评论”。沿用 tb_blog_comments，不把现有表名、回复字段或评论数量当作完整评论系统已实现。

## 写入为什么需要事务

POST /blog-comments 只接受 blogId、content。作者来自认证会话，parentId/answerId/liked/status 固定为 0，主键和时间由数据库生成。客户端传入实体控制字段会被忽略；评论内容是原样保存的不可信文本，展示方必须按纯文本转义，不能直接插入 HTML。

服务先校验请求和会话，再在同一个 Spring 事务中：

1. SELECT 动态 ID FOR UPDATE；不存在返回 NOT_FOUND，不写评论。
2. 插入评论，并检查受影响行数和生成的主键。
3. UPDATE tb_blog SET comments = COALESCE(comments, 0) + 1，并检查恰好更新一行。
4. 读取数据库生成的创建时间，组装显式响应白名单，然后提交。

插入、计数更新或回读失败均抛出运行时异常，事务回滚。服务方法内捕获的数据库异常转换为通用 INTERNAL_ERROR，不返回或记录驱动中的 SQL、内容或连接信息。已知受影响行数异常为 OPERATION_FAILED。这不是数据库异常都代表“根本没有提交”的协议：如果响应在提交后丢失，用户无法据此判断是否创建成功。

锁定父动态，使同一动态的本用例写入串行，避免旧计数读改写丢失；SQL 本身使用原子增量，而非应用先读数字再写回。不同动态可以并行，但未做负载/容量或热点吞吐评估。其他绕过本服务的写入、历史错误计数、未来删除/审核如何维护计数均不由本阶段解决。

使用 COALESCE 是兼容历史 NULL，不是重建历史总数。迁移不把旧 status=NULL 改成正常，也不修复旧父子关系或计数。动态 comments 是沿用的聚合字段，不保证等于当前“可见正常一级评论”的条数。

## 为什么不是 current 页码

GET /blog-comments/of/blog/{blogId} 要求认证；size 默认 20、范围 1..50，beforeId 可选且必须正数。查询条件为同一 blog_id、parent_id=0、answer_id=0、status=0；被举报(1)、隐藏(2)、NULL 状态和回复均不返回。

ID 降序，下一页以 id < beforeId 筛选，取 size+1 条判断 hasNext，只返回前 size 条。nextBeforeId 是最后一条实际返回的 ID，不是额外探测行的 ID；没有下一页时为 null，在现有 NON_NULL JSON 配置下省略。返回 items/hasNext/nextBeforeId，不查询 total，也不提供任意页跳转。

第一页 [9,8]、探测到 7，则游标为 8；下一页查 id<8。期间新插入 ID=10 不会把已返回的 9、8 挤进下一页。排序依据是 ID，不承诺任意历史手工导入行的严格时间顺序。

存在性和列表读取处于只读 REPEATABLE_READ 事务内。单次请求共享快照，不代表跨多次翻页固定快照；未来状态变更/删除、手工补入更小 ID 或不同读写路径仍可改变后续结果。游标是排序边界，不是授权凭据，其他动态的正数 ID 也只作为边界，不能越过 blog_id 筛选。

响应仅包含字符串 id/blogId/userId、content 和 createTime，避免超过 JavaScript 安全整数时丢失精度。内部 parentId/answerId/status/liked/updateTime 不暴露。请求 DTO 以 Java Long 解析，可接受数值或十进制字符串，客户端大 ID 应使用字符串；范围仍受 Java 有符号 Long 限制，不声称支持 BIGINT UNSIGNED 的全部取值。

## 索引和迁移边界

V007 建立 (blog_id,parent_id,answer_id,status,id)，为等值过滤后 ID 范围/排序提供候选索引。当前验证结构和查询正确性，不等于 EXPLAIN、热点性能、执行计划稳定性或生产容量证明。

fresh bootstrap 在 V006 后执行 V007。已有卷不会因重启自动迁移：先备份、核对表和同名索引定义，再手工应用；DDL 可能锁表且不是业务事务回滚。按索引名重复执行时不重建，不自动修复同名错误定义。回退旧应用可保留新索引/新增数据，但旧版本的空 Controller 不提供评论 API；删除索引是另行评估的 DDL，不自动删除评论数据。

## 验证分层与后续

模拟测试证明校验/白名单/路由和安全链行为，不证明事务回滚。显式 BlogCommentsMySqlIT 使用自有临时 MySQL、真实 MyBatis 和 Spring 事务代理，验证并发计数、异常回滚及分页；默认测试发现规则不执行 *IT。Compose 在目标 MySQL 8.4 / Redis 6.2 上进一步验证实际 HTTP、身份与 DB 状态，两层不能互相替代。

无幂等键和防重复提交保证，无评论回复、点赞、删除、审核操作、通知、限频/敏感词系统或前端。后续需先定义这些操作与计数/可见性的一致性，再选一个最小用例，不自动展开。

实现及本轮实际证据见 [Phase 7B](../refactor/24-phase-7b-comments.md)；当前参数与响应见 [API 目录](../api/endpoints.md)。
