# Phase 7O：管理员隐藏直接回复

日期：2026-10-15。基线 `f7cf685b7924f4e6a8723ce2ea950a29c5322c9c`。本阶段范围仅为 ADMIN 隐藏一层直接回复及独立审计，不增加审核读取路由、恢复、举报、通知、内容快照或多级回复。

## 实现边界

源码已增加 `POST /admin/blog-comments/replies/{replyId}/hide`，Controller handler 为 `hideReply`，方法权限 `hasRole('ADMIN')`。复用隐藏请求 DTO；`reason` 非空白且最多255个Java UTF-16单元，操作人从会话取得。成功为HTTP 200 `Result.ok()`，不含data。

这是管理动作，不改变回复的作者撤回权限规则之外的其他生命周期。目标必须满足 `parent_id>0`、`answer_id=parent_id`，因此只允许一层直接回复，不接受多级或格式错误的关系。服务先校验replyId、reason和当前操作人正ID，然后在首条SQL（包括候选查询）之前确认实际事务已激活、事务同步已激活且事务非只读；任何门禁不满足都在执行SQL前拒绝。

## 锁顺序、状态和重复请求

写操作统一按以下顺序取得锁，并在锁定读中重新核实目标：

1. 校验参数、reason和会话操作人；事务门禁确认actual、synchronization active且非只读。
2. 候选读取目标回复，取得 `blog_id` 与 `parent_id`。
3. 锁动态。
4. 锁定同一动态下规范一级根评论（`parent_id=answer_id=0`）；根的status不限制。
5. 锁目标直接回复，复核动态、根关系、`answer_id=parent_id` 以及status为0或2。

锁定读是InnoDB的当前读，可以检查等待锁期间其他事务已提交的状态；普通一致性读可能沿用REPEATABLE READ旧快照。根被隐藏或作者撤回后仍可管理其合法回复；动态被作者撤回后也不改变这条管理动作的资格，只要动态、规范根和回复行都还存在且关系合法。

首次隐藏将回复状态从0改为2、动态 `comments` 计数按零下限减一、并插入一条本功能审计，三者在同一可写事务内完成且每个写入恰好影响一行。任一影响行数不为1，返回422并整体回滚。`DataAccessException` 转为不带cause的通用500。

status=2本身不足以证明本功能曾成功。仅当存在同一 reply/blog/root 且 `from_status=0,to_status=2` 的V013审计时，重复调用返回200；不写、不再扣数，也不覆盖首次操作人或原因。旧status=2而无匹配本用途审计返回404。status为1、3、NULL，目标缺失，缺动态/根，或回复关系非法/多级，均404。ADMIN guard之外的角色按安全层返回403，未认证按401。

成功后作者不能通过 `DELETE /blog-comments/replies/{replyId}` 撤回已隐藏的status=2回复。系统保留回复正文、liked、回复与根的关系、根和兄弟回复；不级联改状态。根隐藏/撤回影响根自身的可见性，不会替代对回复的这次独立管理操作。

## V013、计数与部署

V013 创建独立表 `tb_blog_reply_hide_audit`，以 `UNIQUE(reply_id)` 限制一条回复一条本用途审计。记录 `reply_id`、`blog_id`、`root_comment_id`、`operator_user_id`、`reason`、`from_status`、`to_status`、`create_time`。表不加外键，不回填没有审计的旧status=2回复，不保存正文快照或TTL。7G/7H读取V008根评论隐藏审计；它们不会读取、分页或导出V013回复记录。本阶段没有审计读取路由。

fresh bootstrap 顺序为 schema 后V001…V013。已有数据库先备份、核对结构，再审查V013表及唯一约束后升级应用；已有卷重启不自动迁移。`CREATE TABLE IF NOT EXISTS` 只避免创建已有同名表，不修复同名但结构错误的表。回退时保留V013和审计记录；不得删除审计、恢复回复状态或手工补计数来伪造无损回退。7K引入动态withdrawn标记；7J及更早应用不识别该标记，不能直接回退。

动态 `comments` 是随写操作维护的派生数，不是当前可见评论数量的权威查询，也不保证与历史行聚合相等。零下限只避免递减到负数，不修复旧数据或历史偏差；本操作不回填、不重算计数。

## 红证据、整合差异与验证

首次定向HTTP mock夹具 `adminDirectReplyHideHasItsOwnEndpoint` 在生产路由尚未添加时，预期200、实际404。它只证明当时应用路由缺失，不证明MySQL行为、事务原子性、计数或审计写入。

初始HTTP红证据之外，整合时发现并由父代理按源码核对修正的三项差异：

- API目录测试的旧 `AdminBlogCommentsController` 路由数断言为3，新route纳入后应为4。
- `docs/api/endpoints.md` 的接口表漏列新route，父代理已补齐。
- 新unit测试的mock读调用缺少verify断言，父代理已补齐读调用核验。

源码/机器目录当前有49个应用映射、13个Controller，AdminBlogCommentsController有4条；7N及此前记录的48/13是各历史阶段统计。API目录 `source_revision` 仍表示目录初建来源，不因本阶段改写。

### 阶段验证

- 定向检查134项通过：129项加API目录5项；该数不与全量package统计相加作为覆盖总数。
- Java 8最终 `clean package` 默认统计470项，0失败/错误，4项原有设计skip；包含API目录检查，不代表470项都是本阶段新增。Corretto 1.8.0_492、Maven 3.9.9、Boot 2.7.18、MyBatis-Plus 3.4.3保持不变；默认Surefire不发现 `*IT`。
- 五套显式IT共113项通过，0失败/错误/skip：评论MySQL 63项（原51项加本阶段12项）、FeedDelivery 24项、Publication 5项、Edit 14项、Redis 7项。本机私有端口MySQL 9.6.0和Redis 8.6.2；显式IT无skip。
- 评论MySQL竞态用例显式使用 `TransactionTemplate` 创建REPEATABLE READ旧快照；独立winner提交后，普通audit count仍为0，随后hide路径的锁定当前读仍成功。该结果只说明此测试场景中旧快照与当前读的差别，不外推生产性能。
- 目标Compose完整44组通过（原41组加本阶段3组），MySQL 8.4.11 / Redis 6.2.24。新3组覆盖真实角色与身份、reason/正Long/伪造字段边界、首次操作人原因保留、原文/根/兄弟保留、列表过滤、V013重复保留及旧根审计读取隔离；非法关系/状态/缺失资源拒绝、隐藏/撤回根和撤回动态下管理、NULL/0/1/7计数下限均通过。状态/计数/审计三类真实SQL异常及零计数更新行数422验证完整回滚、通用错误脱敏；四个并发HTTP全部200且只提交一次隐藏、一条审计、一次扣数。
- 真实MySQL还覆盖十个并发隐藏、隐藏与作者撤回竞争、初查后独立事务更改关系/状态的锁定重查；三个真实写入执行成功后拦截器报告0行，均422并回滚。事务门禁覆盖未代理和加入只读事务且首条Mapper前拒绝。IT不替代真实HTTP，定向mock不替代事务证据；未执行浏览器、生产迁移、强杀、容量或HA验收。
- 自建项目的容器、网络、合成数据卷和项目镜像均由脚本清理并按明确项目标签独立读回确认无残留；两轮显式IT各自记录的六个私有端口均无监听。只使用自建服务和合成身份，不读取个人.env、凭据或连接共享DB/Redis。原教学工作区未修改，保留原有未跟踪文件。
- 25个阶段文件中11份Markdown的相对链接存在性检查、脚本AST/sh语法、`git diff --check`及API漂移检查通过；选定高可信私钥/AWS/GitHub token/个人路径模式无命中，不宣称完整安全审计。打包jar中的新增Controller/服务/Mapper、XML和V013已与当前编译资源逐字节核对。

以上实现、选定验证和资源清理完成。随后scoped commit、新未参与实现的Luna独立只读提交审查、普通push、fetch/readback；最终SHA与同步状态以交付对话为准，完成后停在本阶段。

阶段范围和当前学习解释见[直接回复隐藏学习笔记](../learning/direct-reply-moderation.md)。
