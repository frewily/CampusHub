# ADR 0005：先附着有效期，再发布新会话身份

日期：2026-10-26；状态：本阶段实现决策。适用现有单Redis与同格式会话，不涉及生产部署。

## 问题

7AD真实故障证明HSET身份成功而过期设置失败时，可留下含用户ID的无TTL Hash；新账号DB回滚也不删除Redis写入。单纯分包不能消除该风险。

## 决定

保持原UUID、login:token:{token} key、UserDTO字段映射和有效期，使用Redis6支持的单key Lua。参数为原分钟常量等值毫秒及三个字符串值，正整数TTL先验证；已有任意类型key均拒绝覆盖，不删除、不延长TTL。

新key先写nickName空串（无id），再PEXPIRE，成功后才HSET三个身份字段。失败用pcall尝试DEL并返回失败，Java仅认可返回1。脚本隔离执行阻止并发请求插入这三个步骤；不是Lua错误自动回滚。TTL和DEL同时失败时可残留无ID占位，原认证filter因缺ID不建立认证，权限恢复后可按原规则删除。

## 没选的方案

- MULTI/HSET/EXPIRE或普通pipeline并不回滚运行时命令错误，仍可能保留身份无TTL。
- HSET完整身份后pcall EXPIRE再DEL，若过期与清理同时拒绝，仍保留含ID的无TTL身份。
- 将会话改成SET PX JSON能单命令写入，但会改变当前Hash读取协议和所有客户端；本阶段不做该迁移。
- 双key暂存/rename增加暂存格式与集群槽位要求；现有单key先隐藏身份可以更小范围修复已验证的发布顺序。

## 代价与边界

运行ACL需允许EVAL/EVALSHA、EXISTS、HSET、PEXPIRE，DEL用于尽力清理；缺权限时拒绝签发，不回退旧非安全路径。只有一把key，可使用标准单槽脚本，但未做Redis Cluster/HA验收。成功会话数据格式不变，增加的Lua资源必须随完整jar发布。

不自动扫描/修复历史无TTL身份，不改认证filter的续期，也不保证网络超时后没有已完成的有TTL会话。清失败计数或DB提交在签发之后仍可能失败，不实现DB/Redis原子性。清理失败占位可能占用内存，运维清理/历史治理需独立范围，不能把安全的发布次序说成所有错误零残留。
