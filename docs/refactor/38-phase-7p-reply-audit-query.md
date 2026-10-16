# Phase 7P：管理员查询单条回复隐藏审计

日期：2026-10-16。基线 `71c1fadef701f617ec7c9f6ca22874f355d70854`。本阶段仅补7O的单条审计读取闭环，不改变隐藏写流程，不增加分页、导出、恢复、举报、通知或新迁移。

## 契约与实现范围

新增 `GET /admin/blog-comments/replies/{replyId}/hide-audit`，handler `queryReplyHideAudit`，仅ADMIN可调用。replyId须为正Java Long；匿名、停用、无有效角色401，有效USER/MERCHANT403，即使是回复作者或原操作人也不能读取。

响应为200 `Result.ok(dto)`；专用DTO只有九个元数据字段：字符串 `id`、`replyId`、`blogId`、`rootCommentId`、`operatorUserId`，以及 `reason`、`fromStatus`、`toStatus`、`createTime`。五个ID以十进制字符串返回，不返回正文、用户资料或当前业务状态。

服务校验正ID及正会话操作人，使用只读REPEATABLE READ事务；Mapper按V013的唯一reply_id参数化普通SELECT，5秒语句超时，不加锁、不连接当前回复/根/动态/操作人。缺审计404，不从旧status=2推断历史；数据库访问异常映射为无cause通用500。该查询读取已提交且对当前快照可见的存储元数据，不是当前状态或防篡改证明。旧快照可以暂时看不到之后提交的审计。

V008根评论单条/分页查询仍只读V008；新路由只读V013。审计在当前业务行或原操作人行被外部移除后仍独立保留。此功能不更新、删除、修复审计，不改变状态或计数。

## 兼容与部署

没有新DDL；依赖7O的V013及其唯一reply_id约束。全新库仍按schema→V001…V013→seed初始化。已有库须先备份、核对并审查应用V013，现有卷重启不自动迁移；`CREATE TABLE IF NOT EXISTS`不修同名错误形状。回退7O仅失去新读取入口，应保留审计和状态/计数；7J及更早不识别动态withdrawn标记，不能直接回退。

## 验证与交付

按系统化调试先在旧生产代码运行 `adminReplyAuditLookupHasItsOwnEndpoint`：有效ADMIN预期200、实际404。只说明旧路由不存在，不是数据库失败或事务证据；确认Controller/Service/Mapper均无回复单条读取，再复用根审计只读模式补最小实现，没有改动7O写流程。

- 144项定向检查通过，0失败/错误/跳过：新service与静态契约5、新HTTP5，以及原评论/回复/撤回/审核/审计、安全、迁移和目录回归134。mock权限/异常测试不冒充真实事务证明。
- 最终Java8离线 `clean package` 默认统计480项，0失败/错误，4项原有设计跳过。Corretto 1.8.0_492、Maven 3.9.9、Boot 2.7.18、MyBatis-Plus 3.4.3不变；默认Surefire不发现 `*IT`，定向统计不与全量相加作为覆盖总数。
- 五套显式IT共122项通过，0失败/错误/跳过：评论MySQL72（原63、新9）、投递MySQL24、发布MySQL5、编辑MySQL14、Feed Redis7。本机自建MySQL9.6.0/Redis8.6.2与随机localhost端口。新SQL在真实MySQL/Spring AOP/XML夹具中被断言实际只读RR事务；五个大ID及UTC时间映射、首记录保留、V008隔离、缺审计不推断、脱离合成业务/操作人行保留均通过。
- 真实审计INSERT尚未提交时，独立读不阻塞且404；提交后新事务可见；真实INSERT后hook报告0行，隐藏写方422整体回滚，读方始终无审计。显式只读RR旧快照在独立隐藏提交后仍404，新事务才读到记录；另一事务持有审计UPDATE锁时，普通查询返回旧已提交元数据，不等待其释放。临时移走自有审计表时通用500且无cause，finally恢复表后可读。

- 打包jar在新鲜Compose完整46组通过（原44、新增2），MySQL8.4.11/Redis6.2.24。真实HTTP校验当前ADMIN/原操作人降权、匿名/停用/无角色、USER/MERCHANT、非法/溢出ID；精确九字段与五个大字符串ID、首次原因/操作人及时间保持、重复读取无写、V013重复执行保留、V008单条/分页隔离均通过。缺记录不推断；自建UPDATE拒绝触发器下读取成功且行/计数快照不变。临时移走V013时通用500，恢复后可读；删除明确自建的回复/根/动态/原操作人后，另一有效ADMIN仍读取原元数据。仅用例夹具移除合成行，不新增业务删除功能。
- 脚本清理自有Compose项目的容器、网络、合成卷和项目镜像后，按明确项目标签独立读回确认无残留；显式IT日志记录的六个私有端口无监听。只访问自建服务、合成身份与测试配置，未读取个人.env、凭据或连接共享DB/Redis。原教学工作区未改，既有未跟踪文件保留。
- 23个阶段文件、11份Markdown相对链接存在性检查、脚本AST/JSON语法与 `git diff --check`通过；源码/机器目录/接口表50条一致，`source_revision`仍为目录初建来源。原63个MySQL测试名字全部保留。选定高可信私钥/AWS/GitHub token/个人路径模式无命中，不宣称完整安全审计；jar内六个变动类/XML与当前编译资源逐字节核对。

以上为选定本机验证，不代替生产迁移、浏览器、容量或HA验收；测试数量与AI辅助实现不能证明个人已独立掌握。

完成实现、验证和清理后，scoped commit→新未参与实现的Luna独立只读审查实际SHA→普通push→fetch/readback，随后停止，不自动进入下一阶段。
