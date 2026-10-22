# 关注写入：拆开依赖，不悄悄改变一致性

Phase7U在7T读取切片之后迁移PUT关注/取关，接口形状和业务规则不变。先对照旧Controller→FollowServiceImpl→MyBatis/Redis与Blog发布依赖，再把身份接收、应用编排、关系写入、投影写入分开。

FollowCommandService只依赖FollowCommandRepository、FollowProjectionWriter和Clock；身份由Controller从UserHolder取得。应用服务不依赖Spring、ThreadLocal、Redis或通用IService，不把Result当业务返回。基础设施仍复用Follow实体/Mapper，未迁移全social或全部共享类型。

## 重试成功不等于全局幂等

关注先COUNT，存在则不INSERT；不存在尝试INSERT，唯一键冲突视为关系已存在。存在/创建成功才ZADD，重复请求也会刷新毫秒分数，原TTL不主动改变。Clock让测试固定时间，生产仍取调用时的系统毫秒。没有额外验证目标用户存在/停用状态，不能借拆包加入新业务规则。

取关先DELETE；只有真正删除关系才ZREM。关系已经没有、Redis却残留成员时，重复取关直接成功而不修复投影。这是保留的旧边界，不是推荐的最终一致性方案。

MySQL写入与Redis更新没有跨库事务。Redis失败可返回通用500，但SQL已提交；重复关注能再次刷新投影，重复取关却不保证清理残留。SQL异常不继续写投影。不要新增@Transactional或静默吞错来掩盖这种差异，也不要将一次重复成功当成并发关注/取关已线性化的证明。

## 迁移与证据

公开写用例从旧IFollowService移除，旧FollowServiceImpl仅保留Blog发布使用的通用关系读取，不改变Feed收件人快照/投递逻辑。移除旧内部Java方法不保证二进制兼容，必须clean构建完整jar。

测试分别覆盖纯编排、真实适配器、HTTP认证/参数和全新Compose。合成数据、mock、真实组件和真实HTTP证据分开，不将本机通过说成生产可靠性/容量结论。原两项写入回归随用例迁移而非删掉断言。详细实际统计见[阶段记录](../refactor/43-phase-7u-follow-command-module.md)。

可复制Prompt：仅迁移既有关注写用例到social，保留HTTP、角色、会话actor、MySQL唯一键与Redis ZSet操作顺序；通过两个写端口和Clock隔离依赖。验证重复/竞争/返回false/SQL和Redis异常、陈旧投影及TTL；不改发布链、不加补偿、不声称全模块已迁完。

个人掌握仍需独立确认。
