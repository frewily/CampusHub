# Phase 2B 会话生命周期与登出验证记录

## 1 阶段范围

本阶段沿用现有 Redis Token 认证方式，补齐验证码安全、登录失败限制、登出撤销和请求线程上下文清理。不引入 Spring Security，不实现角色或资源归属授权。

完成内容：

- 删除验证码明文日志；发送接口只返回通用成功信息。
- 为同一手机号增加 60 秒验证码发送冷却，冷却键通过 Redis 原子 `SET NX` 创建。
- 为验证码登录增加失败计数：Lua 原子完成计数和首次 TTL 设置，10 分钟窗口内第 5 次失败开始返回 HTTP 429 和 `RATE_LIMITED`。
- 使用 Redis Lua 原子比较并删除验证码，保证一个验证码最多成功消费一次。
- 登录成功后清理失败计数，并继续只把脱敏 `UserDTO` 写入 Redis Token 会话。
- 实现 `/user/logout`，删除当前 Redis Token。
- `RefreshTokenInterceptor` 在请求开始和完成时清理 `UserHolder`，避免线程池复用造成用户上下文串请求。
- 匿名访问受保护接口统一返回 HTTP 401 和 `AUTHENTICATION_FAILED`，不再返回空响应体。
- 移除内部服务方法中未使用的 `HttpSession` 参数；HTTP 路由保持不变。

## 2 生命周期规则

| 对象 | Redis key | 生命周期 |
| --- | --- | --- |
| 验证码 | `login:code:{phone}` | 2 分钟；成功校验时由 Lua 原子删除 |
| 发送冷却 | `login:code:cooldown:{phone}` | 60 秒；阻止连续发送 |
| 登录失败计数 | `login:failure:{phone}` | 首次失败后保留 10 分钟；成功登录后删除 |
| 登录会话 | `login:token:{token}` | 沿用既有滑动过期；访问时刷新，登出时立即删除 |

当前失败限制按手机号统计。它能限制单手机号验证码枚举，但不替代网关级 IP、设备或全局流量限制。

## 3 自动测试

使用 JDK `1.8.0_492` 执行：

```bash
JAVA_HOME=/path/to/jdk8 ./mvnw -o -Dmaven.repo.local=/private/tmp/campus-m2 clean test
```

结果：31 个测试，0 失败，0 错误，1 个依赖外部服务的手工集成测试按设计跳过。

新增回归测试覆盖：

- 重复请求验证码触发 `RATE_LIMITED`，且不会覆盖验证码。
- 获得发送冷却锁后只向 Redis 写入 6 位验证码，不通过响应返回验证码。
- 错误验证码增加失败计数，并在第一次失败时设置窗口 TTL。
- 第 5 次失败返回 `RATE_LIMITED`。
- 成功登录通过 Lua 消费验证码、建立脱敏 Token 会话并清理失败计数。
- 登出删除 Token 并清理当前线程用户。
- 匿名请求开始时清除遗留用户，认证请求结束时清理 `ThreadLocal`。
- 匿名受保护请求抛出类型化认证错误。

## 4 真实启动与 HTTP 验证

使用 JDK `1.8.0_492`、本机 Redis 和随机 HTTP 端口启动应用。Spring Web、Redis、Redisson、拦截器和订单 Stream 消费组均正常初始化。

匿名请求：

```text
GET /user/me
```

实际返回 HTTP 401：

```json
{"success":false,"errorCode":"AUTHENTICATION_FAILED","errorMsg":"请先登录"}
```

登出验证使用独立的假 Token Redis Hash，并设置 120 秒自动过期：

1. 写入 `login:token:codex-phase2b-verification-token`，写入前确认键不存在。
2. 携带该 Token 请求 `POST /user/logout`，实际返回 HTTP 200 与 `{"success":true}`。
3. Redis `EXISTS` 返回 `0`，确认 Token 已撤销。

一次性验证码另使用独立的两分钟临时键验证 Lua：第一次以正确验证码执行脚本返回 `1` 并删除键，第二次使用同一验证码执行返回 `0`。这证明验证码比较与删除在 Redis 内一次完成，重复消费不会成功。

失败计数 Lua 使用另一个临时键验证：首次执行返回 `1`，随后读取到正 TTL；第二次执行返回 `2`，验证后主动删除该键。

验证后应用正常结束，Maven 退出码为 0。三个临时 Redis 键均已删除。该验证没有使用真实用户凭据，也不代表真实数据库登录或短信发送端到端已验证。

## 5 兼容性与未完成项

- `/user/code`、`/user/login`、`/user/logout` 及其他 HTTP 路径和方法保持不变。
- Redis Token 前缀和既有 Token 响应形式保持不变；登出现在由未实现错误变为真实撤销。
- 新增 `RATE_LIMITED` 错误码和 HTTP 429；客户端应按错误码处理冷却和失败限制。
- 当前项目只生成并缓存验证码，没有接入短信服务商，不能视为真实短信链路。
- IP、设备和全局流量限制、验证码发送审计以及分布式风控尚未实现。
- USER、MERCHANT、ADMIN 角色和资源归属授权属于 Phase 2C。
- 账号禁用、权限矩阵和完整 Token 安全策略将在授权阶段继续处理。
- 未执行真实数据库登录端到端、并发压测或性能测试。

## 6 阶段结论

Phase 2B 已形成可撤销、可清理、带基础防枚举限制的 Redis 会话生命周期。自动测试和真实 HTTP 登出链路通过，Phase 2C 可以在此基础上增加角色与资源授权。
