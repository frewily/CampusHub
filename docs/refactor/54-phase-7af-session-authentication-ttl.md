# Phase 7AF：请求级会话TTL认证门禁

日期：2026-10-26 开始，2026-10-27 收尾。基线 `b21c34e1470d9b2f7ccb7e665e9eed93eb907fd2`，开始前fetch确认无分歧、工作树干净。

## 范围与行为变化

RedisTokenAuthenticationFilter仍先HGETALL、原DTO映射、ACTIVE/当前角色读取；在设置SecurityContext/UserHolder前新增session-authenticate.lua条件续期，删除原Java直接expire。只认可返回1；null/0/未知值不建立身份，命令异常仍抛出且finally清理上下文。

脚本单key两参数，当前类型Hash且id与本次快照原始字符串相同才检查PTTL；正数时PEXPIRE成功才认证。无TTL/临界0的匹配身份尽力DEL并拒绝，清理拒绝仍不授权；key已删、变类型或id改变则不删不续期。大ID不进Lua number，保持原分钟常量等值毫秒。旧无TTL会话需重新登录，不批量扫描/修理历史数据，未访问旧key保持不变。

原无id/禁用/无有效角色Java删除支路保持，不宣称所有失效清理竞态已修；DB角色/状态不是冻结快照，也不承诺Lua认证判定后的瞬时撤销。签发/登录事务、API/51映射19Controllers/机器目录、账号仓储、DTO/常量、旧Lua/迁移/POM不改。角色要求和正常成功会话格式/TTL保留，公共路由仍可匿名；依赖异常在filter抛出，当前受保护/error分派可将续期故障转为401，不新增503或MVC typed500协议。

## 本轮验证

- 修复前的真实Redis过滤器回归1项失败：含有效id/角色但无TTL的Hash会进入已认证chain，明确“no-TTL identity must not authenticate”断言失败；原filter还会续上生命周期。保留RED日志后，仅更改认证门禁，同一真实场景转绿，未先删除故障数据制造通过。
- 定向14项通过，0失败/错误/跳过：过滤器5、源码/Lua边界2、真实Redis过滤器7。覆盖原两角色成功与finally清理、未知/null脚本结果不授权、脚本异常chain不运行且上下文清空、原始大ID/前导零/trim、禁用/无角色旧分支；组件用SQL mock的确定性角色回调实际删除/换id/换类型Redis key，验证不授予旧快照身份，当前Lua支路不删/不续替换数据（含替换成无TTL的不同id），真实PEXPIRE拒绝不运行chain，清理拒绝仍不授权。
- 夹具迁移初次编译发现6处Mockito anyString/Collections导入遗漏，分两轮补全；单测静态复核还修正了超Long范围的大ID输入和过拟合局部变量名/diamond语法的检查，保留大于JS安全整数与“id不转Lua数字”的实际约束，不改生产行为。原成功会话夹具按具体token/raw-id为新续期脚本补成功桩，未对所有请求全局授予认证；订单真实HTTP夹具明确补TTL而不再依赖旧自动复活。失败日志保留，不放宽认证断言。
- 初次Java8离线clean package共679项，0失败/错误、4项原设计跳过；随后为错误分派行为增加1项对照，最终重跑680项。Corretto1.8.0_492、Maven3.9.9/依赖不变，定向与默认不相加冒充覆盖率。
- 十七套显式IT共214项，0失败/错误/跳过：本轮原15套192项重跑，加认证Redis7和受影响订单MySQL/Redis15。自建MySQL9.6.0/Redis8.6.2和随机localhost端口；13个日志记录端口独立确认无监听。订单夹具虽仅加TTL也实际运行，不把修改而未跑的旧IT当通过；组件层SQL mock与真实SQL事务/HTTP层证据分开。
- 首次完整HTTP在续期拒绝场景得到401而夹具预期500。保留失败日志并另建最小独占同版本Compose复现：合法会话正常200，拒绝PEXPIRE时HTTP为401/AUTHENTICATION_FAILED，直接执行同一Redis脚本确为错误回复，不是TTL或id不匹配。再以原SecurityConfig的ERROR dispatch单测验证/error仍受认证保护、错误分派不重读Redis；42项定向/组件对照通过，证明filter异常后原错误分派进入401入口。只修HTTP响应预期并保留无身份/无写入/Hash与TTL不变/恢复重试断言，生产错误策略未改，失败与最小探针资源均已由脚本清理。
- 增加ERROR分派对照后最终clean package共680项，0失败/错误、4项设计跳过；比7AE净增6（filter补3、边界2、ERROR分派1）。最终Surefire XML独立汇总一致。组件测试源码及生产源码未因响应预期修正而改变，沿用本轮已实际执行的214项IT证据，不重复运行未改变的组件。
- 最终全新Compose完整81组通过，result.json独立核对组数与MySQL8.4.11/Redis6.2.24；原77组回归及本轮4组均通过。新增验证有TTL身份先续期、无TTL历史GET与有效受保护写入均401、公共路由仍匿名、未呈现历史不变；真实DEL拒绝仍不授权/不写业务状态；真实PEXPIRE拒绝经原错误分派401，不泄露token、不改Hash/不刷新TTL，恢复后同会话可用。目标Redis另直接验证相邻大ID的原始字符串比较，不冒充完整HTTP竞态。
- 最终成功项目 `campushub-phase5-test-433c253dad09`、初次失败项目 `campushub-phase5-test-867733803f29`、最小探针 `campushub-phase5-test-1f60d5778fc5` 的自有容器、卷、网络与app镜像均独立查询为0。未对其他Docker资源执行清理。
- 最终jar的236个应用class/资源与target/classes逐项一致，打包Lua与源码逐字节一致；HTTP前后有序应用内容SHA-256保持 `9a70a23eca4dab1392d2c62e93d405caa23b25646a15a365eb725e9b4e64b118`（按完整条目名排序，逐项摘要输入为UTF-8条目名、NUL、内容）。仅文档收尾不重建应用。
- 收尾确认32个变更路径中生产代码仅filter与新Lua；diff空白检查、Python语法、API JSON解析、702个文档相对文件链接及高置信度敏感模式扫描通过。原教学目录HEAD仍为 `e9a365ace19e55ad324f546fb1b022fcd1526f75`，仅保留原未跟踪 `.DS_Store` 与 `docx/`；未覆盖原目录。

## 部署与回退

完整clean构建同时发布脚本与filter；成功Hash格式不迁移。需EVAL/EVALSHA/TYPE/HGET/PTTL/PEXPIRE和尽力清理DEL权限，生产ACL未验收；权限不足时失败关闭，不回退旧认证。旧实例混跑或回退旧filter会恢复历史无TTL认证风险，不作为安全回退。

只用独立副本、临时无凭据Maven配置、自建随机localhost Redis/MySQL和全新Compose合成数据；不读个人.env，不接共享服务/生产。故障ACL均finally恢复，精确清理测试自有资源；原教学目录保留。按验证、scoped commit、新Luna独立只读审查实际SHA、普通push与fetch/readback后停止，最终状态在交付对话报告；不等于生产、HA、容量、瞬时撤销或个人掌握认证。
