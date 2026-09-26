# Phase 2C 角色与资源授权验证记录

## 1 阶段范围

本阶段在保留现有 Redis Token 格式、HTTP 路径、数据库表名和 Redis key 的前提下，引入 Spring Security，补齐账号状态、角色和商户资源归属授权。不改造 API DTO，不进入 Phase 2D。

完成内容：

- 使用无状态 `SecurityFilterChain` 替换旧 MVC 登录拦截器；关闭表单登录、HTTP Basic、默认登出和服务端 Session。
- Redis Token 过滤器继续读取既有请求头与 Redis Hash，并在每次请求时从 MySQL 重新查询账号状态和角色。
- 只有 `ACTIVE` 账号可以通过认证；禁用账号的 Redis Token 会被删除。
- 请求结束后同时清理 `SecurityContext` 和 `UserHolder`，避免线程复用导致身份泄漏。
- 新增 `USER`、`MERCHANT`、`ADMIN` 三种账号角色，以及商户主体、商户成员和门店归属模型。
- 商户写操作同时校验 `MERCHANT` 角色、有效商户成员关系和门店归属；`ADMIN` 可以管理门店与优惠活动。
- 普通用户活动接口拒绝 `ADMIN`，即使该账号同时具有 `USER` 角色。
- 新增 HTTP 403 与 `AUTHORIZATION_FAILED`，保持现有统一响应结构。
- Controller 和 Service 双层保护门店及优惠活动写操作，避免内部调用绕开资源授权。
- 通用门店更新保留数据库中的 `merchant_id`，不能通过 `PUT /shop` 转移门店归属。

## 2 认证链路

1. 公开接口直接进入业务 Controller。
2. 其他请求由 Redis Token 过滤器读取现有 Token，请求 Redis 会话。
3. 过滤器根据会话中的用户 ID 查询数据库账号状态和角色，而不是信任 Redis 中可能过期的角色数据。
4. 账号有效时创建 Spring Security 身份，同时写入兼容旧业务代码的 `UserHolder`，并刷新 Token TTL。
5. 方法级权限表达式继续检查角色、商户成员关系和门店归属。
6. 请求完成后清理两套线程上下文；账号禁用、Token 不存在或账号不存在时按匿名请求处理。

这一设计使角色调整和账号禁用在下一次请求立即生效，但也会为每次认证请求增加数据库查询。后续可以在保持即时失效语义的前提下评估带版本号的权限缓存，本阶段不提前引入。

## 3 权限矩阵

| 能力 | 匿名 | USER | MERCHANT | ADMIN |
| --- | --- | --- | --- | --- |
| 登录、发送验证码 | 允许 | 允许 | 允许 | 允许 |
| 浏览热门动态、门店、门店类型和优惠活动 | 允许 | 允许 | 允许 | 允许 |
| 发布或点赞动态、关注、上传图片 | 拒绝 | 允许 | 允许 | 允许 |
| 下单限量活动、签到 | 拒绝 | 允许 | 允许 | 拒绝 |
| 创建门店 | 拒绝 | 拒绝 | 仅限有效成员所属商户 | 允许 |
| 更新门店 | 拒绝 | 拒绝 | 仅限所属门店 | 允许 |
| 创建优惠券或限量活动 | 拒绝 | 拒绝 | 仅限所属门店 | 允许 |
| 删除上传图片 | 拒绝 | 拒绝 | 拒绝 | 允许 |

除显式公开的读取接口外，其余请求首先要求有效认证。角色校验不能代替资源归属校验：`MERCHANT` 只有在 `tb_merchant_member` 存在有效关系，并且目标门店属于同一商户时才能执行管理操作。

## 4 数据库迁移

新增 `V002__add_identity_and_merchant_authorization.sql`：

- 为 `tb_user` 增加默认值为 `ACTIVE` 的 `status`。
- 新增 `tb_user_role`，并为历史账号回填 `USER`。
- 新增 `tb_merchant` 和 `tb_merchant_member`。
- 为 `tb_shop` 增加可空 `merchant_id` 和 `idx_shop_merchant`。
- 历史门店的 `merchant_id` 保持 `NULL`，明确表示平台托管，不自动猜测归属。
- 使用 `INFORMATION_SCHEMA` 和 `CREATE TABLE IF NOT EXISTS` 保护列、索引和表，使一次成功执行后的重复执行安全。

执行顺序：

```bash
mysql -u "$DB_USERNAME" -p hmdp < src/main/resources/db/migration/V001__add_business_unique_constraints.sql
mysql -u "$DB_USERNAME" -p hmdp < src/main/resources/db/migration/V002__add_identity_and_merchant_authorization.sql
```

迁移脚本的结构和重复执行保护已由自动测试检查。当前环境没有可用的 MySQL 凭据，Docker daemon 也未运行，因此本阶段没有在真实 MySQL 实例执行 V002；不能把静态迁移测试视为真实数据库迁移成功。

## 5 自动测试

使用 JDK `1.8.0_492` 执行：

```bash
JAVA_HOME=/path/to/jdk8 ./mvnw -Dmaven.repo.local=/private/tmp/campus-m2 clean test
```

结果：42 个测试，0 失败，0 错误，1 个依赖外部服务的手工集成测试按设计跳过。

新增或更新的回归测试覆盖：

- 真实 `SecurityFilterChain` 对匿名受保护请求返回结构化 HTTP 401。
- 公开登录入口不被 Spring Security 默认登录机制拦截。
- `ADMIN` 调用普通用户活动接口返回结构化 HTTP 403。
- 商户有效成员可以为所属商户创建门店，不能为无成员关系的商户创建门店。
- 禁用账号拒绝认证并撤销 Token；请求完成后两套身份上下文均被清理。
- 角色、商户成员和门店归属的组合判断。
- `ADMIN` 即使同时拥有 `USER` 角色也不能下单或签到。
- 门店更新不能修改现有 `merchant_id`。
- 新账号原子写入默认 `USER` 角色。
- V002 包含账号状态、角色、商户成员、门店归属和重复执行保护。

## 6 真实启动与 HTTP 验证

使用 JDK `1.8.0_492`、本机 Redis、`ORDER_STREAM_CONSUMER_ENABLED=false` 和端口 `18081` 启动应用。Tomcat、Redis、Redisson 和新的 Spring Security 过滤链均正常初始化；验证结束后应用正常退出，Maven 返回成功。

匿名请求：

```text
GET /user/me
```

实际返回 HTTP 401：

```json
{"success":false,"errorCode":"AUTHENTICATION_FAILED","errorMsg":"请先登录"}
```

公开入口：

```text
POST /user/code?phone=bad
```

实际返回 HTTP 400：

```json
{"success":false,"errorCode":"VALIDATION_FAILED","errorMsg":"手机号格式错误"}
```

这证明公开登录入口没有被 Spring Security 默认认证拦截。HTTP 403 已使用真实安全过滤链和 MockMvc 验证；由于本机没有数据库凭据，没有完成真实 MySQL 账号、Redis Token 和 HTTP 403 的端到端验证。

## 7 兼容性与未完成项

- 既有 Redis Token 前缀、请求头、滑动过期和响应形式保持不变。
- 既有 HTTP 路径和方法保持不变；原本未授权的匿名写请求现在稳定返回 401，已认证但越权的请求返回 403。
- 旧 MVC 认证拦截器已删除，身份入口统一到 Spring Security。
- 历史门店不会自动授予任何商户；需要后续由可信管理流程显式认领或分配。
- Spring Boot 启动时仍会输出开发用随机密码提示，但表单登录和 HTTP Basic 均已关闭，该密码不参与当前认证链路。
- V002 尚未在真实 MySQL 执行；真实数据库 Token 认证和 HTTP 403 端到端也尚未验证。
- API DTO 与持久化实体分离属于 Phase 2D，本阶段没有改动该边界。
- 尚未进行并发压测或性能测试。

## 8 阶段结论

Phase 2C 已建立账号状态、角色和资源归属三层授权边界，并以自动测试和真实启动验证了安全过滤链的基本行为。真实 MySQL 迁移与真实数据库身份的完整授权链路仍需在具备隔离数据库凭据的环境补验，不能据此宣称生产就绪。
