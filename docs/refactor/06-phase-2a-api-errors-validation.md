# Phase 2A 类型化错误与参数校验验证记录

## 1 阶段范围

本阶段只建立 HTTP API 的错误契约、输入校验和统一异常处理，不实现登录会话生命周期、角色授权或请求与持久化模型分离。

完成内容：

- 引入 Bean Validation，并在 Controller 边界校验请求体、ID、分页、滚动参数、坐标和上传参数。
- 增加 `ErrorCode` 与 `BusinessException`，将业务失败转换为稳定错误码和对应 HTTP 状态。
- 使用 `GlobalExceptionHandler` 统一处理业务异常、Bean Validation 异常、参数绑定异常、缺失参数、类型错误、不可读 JSON 和未知异常。
- 将原有服务层字符串失败结果迁移为类型化业务异常。
- 修复门店详情接口重复包装 `Result` 的旧问题，避免响应体出现嵌套结果。
- 保留原有成功响应字段、Controller 路径和数据库、Redis 兼容面。

## 2 错误契约

失败响应结构为：

```json
{
  "success": false,
  "errorCode": "VALIDATION_FAILED",
  "errorMsg": "页码不能小于1"
}
```

当前错误分类：

| 错误码 | HTTP 状态 | 用途 |
| --- | ---: | --- |
| `VALIDATION_FAILED` | 400 | 请求格式、绑定或约束校验失败 |
| `AUTHENTICATION_FAILED` | 401 | 登录凭证或验证码不正确 |
| `NOT_FOUND` | 404 | 业务资源不存在 |
| `CONFLICT` | 409 | 重复操作、库存或资源状态冲突 |
| `OPERATION_FAILED` | 422 | 请求合法但业务操作未完成 |
| `NOT_IMPLEMENTED` | 501 | 已声明但尚未实现的接口 |
| `INTERNAL_ERROR` | 500 | 未预期的服务器异常 |

未知异常只向客户端返回通用消息，详细堆栈只保留在服务端日志中。成功响应仍使用 `success`、`data` 和 `total`；Jackson 的非空字段策略会省略成功响应中的空错误字段。

## 3 校验边界

- 登录和验证码发送校验手机号与验证码格式；可选密码仅在提供时校验格式。
- Controller 对资源 ID、页码、Feed 偏移量、经纬度和关注状态执行方法参数校验。
- 请求体增加空值和级联校验入口；实体字段级写入约束暂不扩散，因为新增与更新场景的约束不同，留到 Phase 2D 使用专用 API 模型表达。
- 服务层仍保留关键业务前置检查，防止非 HTTP 调用绕过业务规则。

## 4 自动测试

使用 JDK `1.8.0_492` 执行：

```bash
JAVA_HOME=/path/to/jdk8 ./mvnw -o -Dmaven.repo.local=/private/tmp/campus-m2 clean test
```

结果：22 个测试，0 失败，0 错误，1 个依赖外部服务的手工集成测试按设计跳过。

新增回归测试覆盖：

- 无效登录请求体返回 HTTP 400 和 `VALIDATION_FAILED`。
- Controller 标量参数约束经方法校验生效。
- `BusinessException` 映射到指定 HTTP 状态和错误码。
- 未知异常返回 HTTP 500 和通用消息，不把内部异常文本写入响应。
- 关注自己由字符串失败结果迁移为 `CONFLICT` 业务异常。
- 门店详情响应只包含一层统一结果，不再嵌套 `Result`。

## 5 真实启动验证

使用 JDK `1.8.0_492`、本机 Redis 和随机 HTTP 端口启动应用。Spring Web 上下文、Bean Validation、MyBatis Mapper、Redisson 和订单 Stream 消费组均正常初始化。

向真实进程请求：

```text
GET /blog/hot?current=0
```

服务返回 HTTP 400：

```json
{"success":false,"errorCode":"VALIDATION_FAILED","errorMsg":"页码不能小于1"}
```

验证后进程正常结束，Maven 退出码为 0。该检查验证了真实 Spring 方法校验和全局异常处理链路，不代表数据库业务端到端验证。

## 6 兼容性与未完成项

- Controller 路径与 HTTP 方法不变；成功响应字段保持兼容。
- 门店坐标参数仍为可选参数，但移除了会导致类型转换失败的字面量默认值 `x`、`y`。
- 失败请求现在使用语义化非 200 HTTP 状态，并新增 `errorCode`；调用方需要按新的失败契约处理。
- 验证码日志、登出、ThreadLocal 清理、发送频率和失败次数限制属于 Phase 2B，尚未实现。
- 角色与资源归属授权属于 Phase 2C，API 与持久化模型分离属于 Phase 2D。
- 未执行完整数据库业务端到端、并发恢复或性能测试。

## 7 阶段结论

Phase 2A 已建立可测试的类型化 API 错误与参数校验基线。自动测试和真实 HTTP 校验链路通过，后续认证与授权阶段可以基于稳定错误契约继续演进。
