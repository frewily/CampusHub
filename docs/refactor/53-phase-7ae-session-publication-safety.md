# Phase 7AE：新会话发布TTL安全边界

日期：2026-10-26。基线 `b27d24fc15b71e9e2b3d6c9202a8adabe0accf4e`，开始前fetch确认无分歧、工作树干净。

## 范围：明确行为修复

本阶段不是保持7AD所有失败行为的纯分包。针对已复现问题，RedisLoginSessionIssuer改为执行单key login-session-publish.lua：正整数TTL/参数先检查，已有key返回0不改；先HSET nickName空串占位，不含id；PEXPIRE成功后才HSET id/nickName/icon。Java仅认可脚本返回1，null/碰撞0/失败-1/坏参数-2及异常不回token，通用500；原UUID、空值编辑/三字段字符串、key和正常TTL不变。

Lua隔离不等于回滚；失败尽力DEL。过期与清理都拒绝时，可剩仅nickName的无TTL占位，原filter因缺id不建立认证。历史含id的无TTL Hash不自动扫描或修复，filter不改，不得宣称所有会话已治理。新协议只用单key和Redis6常见命令，ACL要求见[ADR0005](../adr/0005-session-publication-safety.md)，不回退旧发布顺序。

原UserServiceImpl/账号仓储、API/Controller/DTO/常量/认证filter、安全配置、已有Lua与V001–V014/POM不改；51映射/19Controllers保持。DB事务与Redis不原子，后续计数清理、DB提交或网络回包不确定仍可能留有TTL会话。

## 本轮验证

- 修复前先改真实Redis过期拒绝断言：旧实现3项中1项明确失败，残留已发布Hash使“过期失败不留会话”断言为false，保留RED日志；不是把7AD旧成功当新保障。随后同一故障场景在新协议通过，最终定向19项全绿（签发单测6、边界1、原登录7、真实Redis5）。
- 整合时新增单测误用当前DefaultRedisScript不存在的getLocation，编译核对实际依赖后改为对照getScriptAsString与classpath资源内容。随后Mockito把可变参数按数组匹配，实际调用为四个字符串，导致严格桩不匹配；改为四个逐项捕获/精确断言，不设lenient、不放宽结果。RED、编译及夹具失败日志均保留，生产安全断言不改。
- 最终Java8离线clean package共674项，0失败/错误、4项原设计跳过；原测试按明确新失败契约替换，不把旧false/null过期返回成功继续当正确行为。Corretto1.8.0_492/Maven3.9.9/依赖不变，Surefire默认XML独立统计一致。成功UUID、精确四参数/毫秒TTL、Hutool原空值转空串/大ID、当前身份不变等断言保留；只有脚本1成功，null/0/-1/-2/意外正数均拒绝。
- 十五套显式IT共192项，0失败/错误/跳过：会话Redis从3变5（增加碰撞与坏参数），其他账号4、验证码4、登出2、Blog用户4、关注18、资料4、券目录4、分类8、签到7、评论82、投递24、发布5、编辑14、FeedRedis7均重跑。自建MySQL9.6.0/Redis8.6.2和随机localhost端口；12个记录端口另行确认无监听。会话IT真实验证成功Hash/TTL/双会话、过期失败可清理时无新key、过期与DEL双拒绝仅留无ID占位、碰撞key/TTL不改和坏参数不写。另以无网络/无端口的一次性目标Redis6镜像探针确认双故障返回-1、id不存在、仅nickName占位及TTL=-1，退出销毁测试容器，不当作完整HTTP证据。
- 全新Compose完整77组通过（7AD的76组按新失败契约保留/调整，另增双故障认证检查），MySQL8.4.11/Redis6.2.24，result.json另行核对组数/版本。真实POST验证原正常token/三字段/空icon/TTL/两会话；Hash拒绝仍500、code已消费、计数不清；过期拒绝时已有/注册账号均500且无新key，注册DB账号/角色回滚，权限恢复与新code重试可用。因全局DEL拒绝会先破坏验证码消费，双故障明确在目标Redis直接执行真实发布脚本，确认仅无ID占位，再恢复权限并经真实GET /user/me得到401及原filter删除占位，不冒充完整POST双故障。全套旧发布/评论/审核/Feed/主机jar/prod缺配置拒绝仍通过，精确Compose项目与探针均独立确认已清理。
- HTTP成功Hash检查改用HKEYS加逐字段HGET，保留精确三字段/空串断言，避免redis-cli输出去尾空白把最后一个空值截断；不修改序列化。最终235个应用class/资源与target/classes逐文件一致，新Lua资源在jar内逐字节一致，HTTP前后有序内容SHA-256均为 `ee93523637a4482f16172a16fed3be99746d23670d1c47b66702998185ab39f1`。
- UserServiceImpl/接口、Controller/51映射19Controllers/机器JSON、账号仓储、DTO/Entity/Mapper/utils、安全filter/config、数据库迁移及旧Lua/POM无差异。8份相关Markdown的401个相对文件链接、Python AST、JSON和diff --check通过；14个变动路径选定私钥/AWS/GitHub token/个人绝对路径模式无命中，不是完整安全审计。原教学目录HEAD及两个既有未跟踪项未改，所有日志/合成凭据仅存私有临时目录，不提交。默认/定向/IT/HTTP统计不叠加冒充覆盖率。

## 部署与回退

完整clean构建发布新Lua和适配器；成功key/Hash/TTL不迁移，不新建DDL，也不操作历史会话。ACL需允许脚本及EXISTS/HSET/PEXPIRE，DEL为失败清理；本机测试权限不等于生产ACL已核验。

回退7AD虽可读取同格式成功会话，但会重新引入已确认的发布风险，不作为安全回退方案。若新脚本权限不满足，须恢复所需权限或停止签发并处理，不把降级到旧非安全路径当成功。

仅用独立开发副本、临时无凭据Maven配置、自建随机localhost Redis/MySQL与全新Compose合成环境，不接共享服务/个人.env、不部署生产。故障ACL均finally恢复，精确清理测试自有key；原教学目录保留。按验证→scoped commit→新Luna独立只读审查实际SHA→普通push→fetch/readback后停止，最终状态在交付对话报告；不是生产、容量、HA或个人掌握认证。
