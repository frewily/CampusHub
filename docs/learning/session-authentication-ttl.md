# 不能先授予身份，再问会话有没有续期成功

## 改变的是认证条件

旧过滤器HGETALL后查账号/角色，先设置UserHolder和SecurityContext，再expire；没有TTL的历史Hash会被认证，续期返回false也不影响当前身份。7AF先保留这条路径的失败证据，再把身份建立放到条件续期成功之后。

新Lua只访问当前key：类型仍Hash、id原始字符串仍相同、PTTL>0，才PEXPIRE并返回1。无TTL匹配身份尽力删并拒绝，即使DEL拒绝也不会授权；key被删、变类型或换成另一id不续期、不在该Lua支路删除替换数据。原账号/角色无效的Java删除支路仍旧，不要由一个保护点推导成所有清理都比较后删除。

id保持Redis原始字符串（含大ID、前导零），不在Lua转浮点；DTO中的Long仍用于原SQL账号/角色查询。昵称与图标仍来自本次Hash快照，不承诺刷新数据库资料。

## 怎么验证竞态而不造竞态

真实Redis组件在模拟SQL角色查询的确定性回调中删除key、换id或换类型，随后运行实际filter，验证不授权、替换数据不删不续期。这是Redis与实际过滤器加SQL mock的组件证据，不是完整HTTP并发或真实MySQL角色竞态。

目标Compose另检查无TTL会话GET个人资料和受保护写入401、公共路由匿名可读、DEL拒绝仍不授权、恢复后清理；PEXPIRE拒绝不运行已认证请求路径且有效Hash不被删除，恢复后可用。直接对目标Redis执行原始id比较，确认邻近大ID不会因双精度而混同，不冒充全HTTP竞态复现。

只处理当前请求token，未访问历史key不自动清理。认证判定后的并发登出不能回溯本次已判定请求，DB/Redis也不是跨存储事务。部署权限/回退和精确结果见[ADR0006](../adr/0006-session-authentication-ttl.md)与[7AF记录](../refactor/54-phase-7af-session-authentication-ttl.md)。
