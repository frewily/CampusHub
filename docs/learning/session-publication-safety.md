# Lua隔离执行，不等于失败自动回滚

本阶段的目标是新签发路径“先有TTL，再有可认证身份”，而不是声称任何Redis错误都没有残留。

旧HSET全部字段→EXPIRE在后一步失败时留下完整身份。把它原样包进Lua或MULTI不能消除运行时错误的已写副作用；HSET后再补偿DEL也受DEL故障影响。

本次单key协议先确认key不存在，再只写nickName空串。没有id，原认证filter不会建立认证；成功附着原25天TTL后，才写id/nickName/icon。隔离执行期间外部客户端不能看到中间步骤。只有返回1才返回token；碰撞、坏参数和失败均拒绝签发，不覆盖已有key。

若PEXPIRE失败，尝试清理；若DEL也失败，占位可能无TTL，但仍没有id。真实Redis组件直接拒绝这两类命令，另在目标Compose Redis上执行协议后用实际HTTP认证验证该占位不可认证。完整POST /user/login在DEL全局被拒绝时，会先影响验证码消费，因此不能把它当作会话清理的精确故障注入。

成功TTL仍来自LOGIN_USER_TTL分钟，传脚本前转换为毫秒；用户大ID始终字符串，不在Lua转number，空值仍沿用旧字段编辑器变空串。原UserServiceImpl的DB事务、验证码消费、状态判断和失败计数清理顺序不改。

红/绿测试先让旧实现因留下完整无TTL Hash而失败，再用同一断言验证新路径；保留成功Hash与TTL、清理也失败的无ID占位、碰撞不覆盖/不延长TTL、坏参数不写等真实Redis检查。

这不清理历史无TTL会话，也不修改现有认证读取/续期；不能推断旧数据全部安全。网络回包丢失、DB提交或失败计数清理失败等仍可能留下已发布的有TTL会话。保障、权限和回退见[ADR0005](../adr/0005-session-publication-safety.md)与[7AE记录](../refactor/53-phase-7ae-session-publication-safety.md)。
