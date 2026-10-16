# Phase 7Q：按动态分页读取回复隐藏审计

日期：2026-10-16。基线 `a43baff37ce240530bdc720837d1dce334625afb`。只补ADMIN按一个动态分页读取V013回复隐藏审计；不改隐藏写流程、V008根审计或7P单条读取，不增全局查询、导出、恢复、举报、通知和审核UI。

## 契约

新增 `GET /admin/blog-comments/replies/hide-audits/of/blog/{blogId}`，handler/service `listReplyHideAudits`。仅当前有效ADMIN；匿名、停用、无有效角色401，有效USER/MERCHANT403，即使是原操作人也不例外。

blogId为正Java Long；复用 `BlogCommentHideAuditPageRequest` 的通用游标约束：可选正Long beforeId、size默认20且1..50。beforeId是审计行ID，不是replyId。只读RR普通参数化SELECT按 `blog_id`、可选 `id < beforeId`、`id DESC LIMIT size+1` 读取，5秒语句超时，不加行锁、不JOIN当前内容/账号、不COUNT。

200 `Result.ok(page)`：items复用7P九字段元数据和五个字符串ID；hasNext由多取一行决定，再裁剪；仅hasNext=true时返回末条已返回审计ID字符串nextBeforeId，否则因NON_NULL省略。无审计、空结果或动态当前不存在均空页成功，不从当前业务状态推断历史。非法参数400，数据访问异常无cause通用500；不更新状态、计数或审计。

每页是独立RR快照，不冻结跨页数据；自增ID分配次序不等于提交次序，晚提交的小ID可能已落在翻过的游标之后，不能承诺跨页无遗漏或完整一致导出。

## V014与部署

V014为已有V013表增加候选 `(blog_id,id)` 索引 `idx_blog_reply_hide_audit_page`，只按同名索引存在性跳过，不修同名错形状。不回填、不改状态/计数、不删除数据，不承诺优化器采用或性能提升。缺V013应失败；全新库schema→V001…V014→seed，已有库先备份、核表/索引并人工审查应用V014再升级。现有卷重启不自动迁移；DDL可能锁表与隐式提交。回退可保留索引/审计，不删历史或恢复状态计数来伪造回退；7J及更早不识别withdrawn，不能直接回退。

## 验证与交付

按系统化调试先在旧生产代码运行 `adminReplyAuditPagesHaveTheirOwnEndpoint`：有效ADMIN预期200、实际404，仅证明原路由缺失，不是数据库失败。确认Controller/服务/Mapper没有该用例后，复用原根分页的只读模式，保持V013/V008对象独立。

整合时新MySQL测试误写了不存在的 `BlogReplyHideAuditPageRequest`，导致测试编译失败；核对契约和唯一引用后改回本阶段复用的 `BlogCommentHideAuditPageRequest`，保留原断言，未为错名另造请求DTO或放宽生产参数以迎合测试。

- 159项定向检查通过，0失败/错误/跳过：新service7、新HTTP7、V014静态迁移1，原评论/回复/审核/撤回/审计、安全、目录和迁移144。mock不替代真实数据库行为。
- 最终Java8离线 `clean package` 默认统计495项，0失败/错误、4项原有设计跳过。Corretto1.8.0_492、Maven3.9.9、Boot2.7.18、MyBatis-Plus3.4.3不变；默认Surefire不发现 `*IT`，定向统计不与全量相加作为覆盖总数。
- 五套显式IT共132项通过，0失败/错误/跳过：评论MySQL82（原72、新10）、投递MySQL24、发布MySQL5、编辑MySQL14、Feed Redis7。本机自建MySQL9.6.0/Redis8.6.2及随机localhost端口；新Mapper在Spring AOP/XML夹具中被断言实际只读RR。
- 真实隐藏顺序与回复创建顺序交错，证明按审计ID分页而非replyId；跨blog/V008隔离、严格游标、空页、size+1裁剪、默认20/最大50均通过。大页51条是合成导入元数据，不是51次实际隐藏或压测。外部移除明确自建业务/原操作人行后仍读保留审计。
- 真实审计INSERT未提交时独立分页不等待、不读取脏数据；提交后新事务可见；真实INSERT后hook报告0，写方422整体回滚，分页始终不可见。显式RR旧快照在独立提交后仍旧页，新事务可见；另一连接持审计UPDATE锁时普通分页读到旧提交元数据。SQL故障临时移走V013时无cause通用500，finally恢复后可读；自建拒绝UPDATE触发器下查询不改状态/计数/审计。
- V014重复执行保留现有记录与(blog_id,id)形状；自建空库缺V013明确失败，故意建立同名(id)错索引后重复不会修复，finally恢复正确索引。不据此承诺性能提升或优化器选择。

- 最终jar在新鲜Compose完整48组通过（原46、新增2），MySQL8.4.11/Redis6.2.24。新增场景验证当前ADMIN/原操作人降权、匿名/停用/无角色与USER/MERCHANT拒绝、非法参数、精确九字段/五个大字符串ID、实际隐藏顺序与回复创建顺序交错、审计游标裁剪/严格边界、跨blog/V008隔离、V014重复保留。导入51条合成元数据验证默认20/最大50及正好size与空尾页，不计为51次实际隐藏。
- 自建拒绝UPDATE触发器下分页成功且状态/计数/审计快照不变；临时移走V013只返回通用500，V008分页仍可读，finally恢复后正常。移除明确自建的回复/根/动态/原操作人后，另一有效ADMIN仍读原元数据。仅测试夹具移除合成行，不新增业务删除能力。
- 脚本清理自有Compose容器、网络、合成卷和项目镜像后，按明确项目标签独立读回无残留；显式IT记录的六个私有端口均无监听。只使用自建服务、合成身份和测试配置，未读取个人.env、凭据或连接共享DB/Redis；原教学工作区及既有未跟踪文件未改。
- 26个阶段文件、11份Markdown相对链接存在性、脚本AST/JSON/sh语法、`git diff --check`及API漂移检查通过；当前51条/13个Controller，管理员评论Controller6条。原72个MySQL测试名字全部保留；jar七个变动类/XML/SQL条目与当前编译资源逐字节核对。选定高可信私钥/AWS/GitHub token/个人路径模式无命中，不宣称完整安全审计。

以上为选定本机验证，不是生产迁移、容量、HA、浏览器或个人独立掌握证明。

完成实现、验证和清理后，scoped commit→新未参与实现的Luna独立只读审查实际SHA→普通push→fetch/readback，停在本阶段。
