# Phase 7A：允许校园动态不关联门店

日期：2026-10-10。应用基线：`8a0a7346b8a21725a98fd8073853941065639bb7`。6F 补齐接口目录后，从原始校园业务目标和 Phase 1A 内容设计中选取最小用例，进入业务深化，不改变暂缓的框架/中间件选型。

## 范围和兼容面

- POST /blog 的 shopId 可省略或显式 null；提供时必须为正数。BlogCreateRequest 保留标题、图片、正文必填与原长度约束，BlogServiceImpl 在非空时再次校验正数。
- 作者仍取会话，主键/计数等由原白名单映射和服务设置。路径、USER/MERCHANT/ADMIN 发布权限、详情/列表/Feed、数字 ID 和 Result 包装不变。
- V006 将 tb_blog.shop_id 从有符号 BIGINT NOT NULL 放宽为可空 BIGINT，不重写旧关联值。fresh bootstrap 在 V005 后应用 V006，完整 schema 与旧历史 hmdp.sql 不改。
- [当前目录](../api/README.md)、README、领域设计的后续状态、迁移与兼容说明同步。清单映射和授权未变化，仍为 37 条应用端、两个单列管理端 GET；source_revision 保留初建映射基线，不把它说成新参数行为的版本证明。

本轮不加入新 Post 表、动态类型字段、纯文字发布、评论、审核、删除、通知或后台；不改点赞存储、Feed 一致性、缓存/订单/Lua、依赖/JDK/Boot 或正常部署配额。提供正数门店 ID 仍未新增存在性/归属证明，内容关联不授权管理门店。

## 实际验证

按系统化调试流程先保留旧 DTO/service，运行新增回归：45 项中 5 失败、1 错误，分别证明旧 NotNull/服务拒绝无关联，以及服务缺少非正数拦截；失败发生在预期校验/断言，而不是依赖缺失。之后仅调整两处规则，不放松标题/图片/正文。

- Corretto 1.8.0_492 / Maven 3.9.9，项目专用离线缓存，clean package 成功：**275 项默认测试，0 失败/错误、4 项教学实验按设计跳过**。新增 7 个测试方法及参数化场景，共增加 16 个默认执行案例。
- 改动的 HTTP 模型/真实安全链与 Service 三类测试共 45 项通过；目录契约 4 项通过，均包含于上述默认套件，不重复计数。安全链使用 mock DB/Redis，覆盖匿名/停用拒绝及三个角色发布；模型测试覆盖白名单、省略/null、正数旧请求、非正数和其他字段限制；服务验证本人作者、各关注者 Feed 和持久化失败不 fanout。
- **2 项显式 BlogPublicationMySqlIT** 通过，本机 MySQL 9.6.0，临时私有进程/随机 localhost 端口。导入生产 fresh schema、V001–V005，每个案例在自有表恢复旧非空形状、插入合成旧帖，再执行 V006 两遍。验证 signed BIGINT/可空、旧内容与关联保留、真实 MyBatis/Service 的 NULL 和正数写入/auto ID、详情/热门/本人分页。关注、用户展示与 Redis 在该层是 mock。
- **11 组真实 Compose 检查**通过，MySQL 8.4.11 / Redis 6.2.24，应用为本轮打包的 Java 8 jar。新增组验证匿名拒绝、省略与 null 门店各一条、正数旧帖一条、作者/计数/主键白名单、详情/本人/热门及真实 Redis Feed、0/负数拒绝且不新增行；原有初始化、V001–V006 重复、图片/权限、重启/依赖故障、主机 jar 和 prod 配置拒绝回归也实际运行。
- 自建 MySQL IT 进程退出；Compose finally 精确清理唯一测试项目的容器、网络、合成卷和本地应用镜像，之后另行只读核对无残留。基础镜像保留，不删除其他项目或用户数据。
- Luna 只读代码预审无阻塞问题，提醒显式 IT 的发现入口；README 已列该 IT。未把历史 82 项 IT、6B 专项诊断/Prometheus 或 6E2 性能记作本轮重跑。
- 文档更新后目录契约 4 项再次通过；9 份变更文档的 165 个相对文件链接、围栏、37 行接口表列宽、bootstrap shell/Python 脚本语法、git diff --check 与变更文件定向私人路径/凭据模式检查通过。模式检查不是全历史密钥审计，文件链接检查不证明源码行锚点语义。

## 复现与限制

Java 8，依赖、mysqld 和 Docker 的要求见 README：

```bash
./mvnw clean package
./mvnw -Dtest=BlogPublicationMySqlIT test
python3 scripts/verify-compose.py
```

Surefire 默认不会发现 *IT；上面的显式命令不能省略。测试只使用自己创建的进程/合成 DB/Redis/会话，不能指向共享库，日志与凭据留在私有临时目录，不加入 Git。

先备份/审查结构，应用 V006，再升级应用。已有卷不能靠重启完成迁移；DDL 可能锁表且不是业务事务回滚。新读取的 shopId 因 NON_NULL 可能省略，客户端须适配；旧客户端、任意脏历史库和真实短信未验收。旧应用可继续向可空库发布关联帖，但有 NULL 数据后不能直接收紧 NOT NULL 或自动删除/伪造关联来回退。

发布沿用 DB 保存后 Redis fanout，没有 outbox/重建或请求幂等；正常 Feed 实测不证明故障投递/事务一致性。完整解释和替代方案见[学习笔记](../learning/campus-post-store-association.md)。

完成验证后按流程提交，由独立 Luna 作提交后复审，通过才普通推送并读回远端。最终审查/推送结果在交付对话报告，不把计划写成已经发生。本阶段结束后停下，不自动开发下一用例。
