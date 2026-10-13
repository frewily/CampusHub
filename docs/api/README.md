# CampusHub 当前 API 目录

机器清单在应用基线 `68aa03bd86cb905c4828cd3a6c3b4616197aeca8` 建立；这是目录初建来源，不表示该提交包含后续路由。参数/响应说明随业务阶段更新：7A 校园动态，7B 一级评论，7C 直接回复，7D/7E 本人回复/一级评论撤回，7F 管理员隐藏一级评论，7G 单条隐藏审计查询，7H 按动态分页读取隐藏审计，7I 纯文字校园动态发布语义（选定本轮实现/验证完成），见[7A](../refactor/23-phase-7a-campus-posts.md)、[7B](../refactor/24-phase-7b-comments.md)、[7C](../refactor/25-phase-7c-comment-replies.md)、[7D](../refactor/26-phase-7d-reply-withdrawal.md)、[7E](../refactor/27-phase-7e-comment-withdrawal.md)、[7F](../refactor/28-phase-7f-comment-moderation.md)、[7G](../refactor/29-phase-7g-comment-audit-query.md)、[7H](../refactor/30-phase-7h-comment-audit-pages.md)和[7I](../refactor/31-phase-7i-text-only-campus-posts.md)。机器清单当前记录 **46 个显式 method/path 映射**（含健康和图片），来自 13 个 Controller。两个 Actuator GET 管理路由另列，不混入应用端数量；原 44 条为 7F 阶段历史基数。

- [逐接口目录](endpoints.md)：方法、路径、身份/资源限制、参数、响应与缺失资源语义，链接 Controller、Service 和模型。
- [机器清单](routes.json)：method/path、Controller、handler 与声明的 `preAuthorize` 原文，用于回归。空字符串仅表示没有该注解，**不代表匿名可访问**；仍受 SecurityConfig 和服务层授权约束。
- [6F 阶段记录](../refactor/22-phase-6f-api-catalog.md)：范围、验证与尚未覆盖的边界。

这是手工维护、带源码漂移门禁的 API 目录，不是完整 OpenAPI schema、在线 Swagger UI、SDK 或新增后台。`HEAD/OPTIONS` 的框架隐式行为、`/error` 和静态资源基础设施不算新增业务接口；并不承诺这些路径统一返回下面的业务包装。

`source_revision` 是建立目录时人工记录的源码基线，不是必须等于每次 Git HEAD 的动态标记。测试只检查其格式，不证明所有字段语义仍与该版本/当前源码一致；修改源码行为时需人工核对说明，并在对应阶段记录注明行为来源，不必重写目录初建来源。

## 认证与角色

默认应用端口 8081，dev 默认仅本机监听；Compose 的发布端口以实际配置为准，复现方法见[仓库 README](../../README.md)。登录使用 Redis 可撤销 opaque 会话，不是 JWT/refresh token，也没有 HTTP Basic 或表单登录。

登录成功的 `data` 是会话字符串，受保护请求使用 **`authorization: <会话字符串>`**，不要加 `Bearer `。身份由 Redis 会话和 DB 的 ACTIVE 状态/有效角色共同确认，成功认证滑动续期；会话时长以 [RedisConstants](../../src/main/java/io/github/frewily/campushub/utils/RedisConstants.java) 的 `LOGIN_USER_TTL` 与分钟单位为准，不把教学项目常见的 30 分钟代入当前值。缺失、无效或已失效会话在受保护路径为 401；公开路径本来允许匿名，无效头不会自动让它变成受保护路径。过滤器访问依赖的异常不等于正常匿名放行保证。

仅 `POST /user/code`、`POST /user/login`，`GET /blog/hot`、`/shop/**`、`/shop-type/**`、`/voucher/**`、`/health/live`、`/health/ready`、`/imgs/blogs/**` 在应用过滤链明确允许匿名；规则按方法区分，不代表同路径所有方法公开。其余请求默认需认证；逐接口的额外角色/归属要求见目录。

- USER/MERCHANT/ADMIN 是 DB 角色，不是客户端传入的字段。身份成立仍不等于可以访问任意资源。
- 门店/优惠活动写入：ADMIN 可管理；MERCHANT 还要满足有效商户成员关系与目标门店归属，创建按 merchantId、更新按现有 shopId 核验。
- 签到、活动参与、本人订单查询/取消：允许有 USER 或 MERCHANT 且不含 ADMIN 角色的身份。ADMIN 即使同时有 USER 也拒绝参与，不代表必须完成学生认证。
- 图片上传、动态发布/点赞、关注写入：方法声明允许三角色；图片删除仅 ADMIN。本人订单仍按 userId + orderId + voucherId 核验，不能凭 ID 查他人订单。

真实行为来源：[SecurityConfig](../../src/main/java/io/github/frewily/campushub/config/SecurityConfig.java)、[认证过滤器](../../src/main/java/io/github/frewily/campushub/security/RedisTokenAuthenticationFilter.java)、[资源授权](../../src/main/java/io/github/frewily/campushub/security/ResourceAuthorizationService.java)。验证码只生成至 Redis，尚未接实际短信供应商；文档没有让读者读取共享验证码或会话的步骤。

## 请求、响应与兼容性

JSON 写入使用 `Content-Type: application/json`；上传用 multipart 字段 `file`，不能把文件放进 JSON。资源 ID 的 path/query 参数通常绑定 Java Long，部分分类参数使用 Integer，以具体 Controller 为准；标有 Positive 的必须大于 0。列表默认值、坐标、排序及必填字段按具体接口/请求模型，不把不同列表统一成同一种分页。

多数业务接口返回 [Result](../../src/main/java/io/github/frewily/campushub/dto/Result.java)：`success`、失败时的 `errorCode/errorMsg`、按接口不同的 `data`、仅有特定调用会填的 `total`。配置 `non_null`，null 字段通常省略；不能要求每次成功都有 data 或 total，用户/资料不存在也可能是空成功。正常健康响应是 `{ "status": "UP"/"DOWN" }`、图片读取成功是二进制、Prometheus 是文本，不用 Result 包装；图片缺失等 BusinessException 仍由全局处理器返回 Result 错误 JSON。

错误同时检查 HTTP 状态和业务字段。正常异常由 [GlobalExceptionHandler](../../src/main/java/io/github/frewily/campushub/config/GlobalExceptionHandler.java)、安全 responder 或局部 handler 设置状态；`Result.fail` 自身不是 HTTP 状态转换器。没有保证任意未知 URL、过滤器依赖异常或框架错误一定遵循完全相同的包装。不存在的用户/资料、已清空列表与 NOT_FOUND 不可混用。

| HTTP | 当前错误码类别（以具体调用路径为准） |
| --- | --- |
| 400 | VALIDATION_FAILED：JSON/绑定/约束/必需参数等失败 |
| 401 / 403 | AUTHENTICATION_FAILED / AUTHORIZATION_FAILED |
| 404 | NOT_FOUND；不是所有“没有记录”都返回它 |
| 409 | CONFLICT、ACTIVITY_NOT_STARTED/ENDED/INACTIVE、SOLD_OUT、ALREADY_PARTICIPATED |
| 422 / 429 | OPERATION_FAILED / RATE_LIMITED |
| 501 | NOT_IMPLEMENTED 在枚举中保留，不等于存在对应产品接口 |
| 503 | ACTIVITY_UNAVAILABLE、ORDER_STATE_UNAVAILABLE、SHOP_STATE_UNAVAILABLE、IMAGE_STORAGE_UNAVAILABLE；健康 DOWN 也返回 503，但无业务错误码 |
| 500 | INTERNAL_ERROR |

完整枚举见 [ErrorCode](../../src/main/java/io/github/frewily/campushub/exception/ErrorCode.java)；枚举中的状态不代表每个接口都会出现该错误。成功建模也不同：旧实体/DTO 的 ID 多为 JSON number，新搜索项/本人订单为字符串，受理响应同时有 legacy 数字 `data` 和无损字符串 `orderId`。客户端应在活动链使用字符串身份，避免 JS number 的 64 位精度损失；不能宣称所有响应已统一 VO/string ID。

活动受理成功是 HTTP 200、`acceptanceStatus=ACCEPTED`，新受理 `replayed=false`，原单重放 `replayed=true` 且同一 orderId；不是落库、支付或完成核销。取消的 DB 状态成功也不等于 Redis 补偿已完成。详细状态与示例见 [准入](../refactor/10-phase-3a-flash-sale-admission.md)、[订单生命周期](../refactor/12-phase-3c-order-lifecycle.md)。日期/时间格式与单位以具体模型为准，LocalDateTime 本身不携带时区，不能自动当所有接口都是带 Z 的 UTC 时间戳。

优先写接口的字段白名单见 [API 模型说明](../refactor/09-phase-2d-api-models.md)；额外实体字段可能被忽略，而非统一拒绝。7A 将 POST /blog 的 shopId 改为省略/null 或正数；7I 当前语义进一步规定：shopId 省略/null 时，images 可省略/null/空串/`String.trim()` 后为空白，归一化为非 null 空串 `""` 后保存；若 shopId 为正数，images 仍必须非空白。title 非空白且≤255、content 非空白且≤2048；images 的≤2048 Java UTF-16 单元约束在归一化前执行。非空图片原串不变，空串按 Jackson NON_NULL 正常序列化。作者来自会话，客户端主键、作者、计数、时间等未知字段忽略；USER/MERCHANT/ADMIN 发布角色沿用现有规则，无有效会话/账号状态/角色为 401。7I 不加路由、表或迁移；实现与选定本轮验证完成，详见[阶段记录](../refactor/31-phase-7i-text-only-campus-posts.md)。7B/7C 支持一级评论/直接回复，7D/7E 分别增加作者本人回复/一级评论撤回（0→3、保留原文、重复不扣计数），7F 增加 ADMIN `POST /admin/blog-comments/{commentId}/hide`：正 Java Long ID、`reason` 非空白且最多 255 个 Java UTF-16 单元，未知 JSON 字段忽略；操作人来自会话，USER/MERCHANT 即使是评论作者也无权隐藏。首次隐藏仅限正常一级评论，状态 0→2、动态计数递减及审计插入同事务；有效重复请求不改首次操作人/原因或再次扣数。隐藏根从一级列表排除，其下新回复列表/创建为 404，回复作者仍可独立撤回；原评论内容/关系及回复行/状态保留。回退应用不会还原已提交的状态/计数，业务事务失败仍整体回滚。部署前需具备 V006/V007/V008；V008 不修复同名错误表或回填历史审计，已有库应备份、审查后手动应用。恢复、回复审核、举报工作流、受保护审计导出和完整管理后台仍未实现，也没有普通券支付/领券闭环、自动超时取消或退款 API。

## 管理面与图片边界

默认独立管理监听器为本机 8082，不是应用 8081。只开放 GET `/actuator/health`（UP/DOWN，无依赖详情）和 GET `/actuator/prometheus`（文本指标）；其余管理 endpoints 默认禁用。容器不发布管理端口，可信 collector 通过 app loopback 采集，见[可观测性说明](../learning/observability-baseline.md)。不能把业务端匿名访问 `/actuator/prometheus` 当成可用 API，也不能把无额外认证的 loopback 指标直接用于公网。

图片上传响应为存储名称 `/blogs/{first}/{second}/{uuid}.jpg|png`；客户端读取需要加 `/imgs`，形成 `/imgs/blogs/...`。删除传存储名称而非读取 URL。图片适配器只接受其生成的受控名称与实际 PNG/JPEG 内容，非任意文件服务；默认单文件 2 MiB / request 3 MiB，其他限制见 [存储实现](../../src/main/java/io/github/frewily/campushub/storage/LocalImageStorage.java) 与配置。遗留 `GET /upload/blog/delete` 是写操作，有缓存/预取语义风险，本轮记录但不改动，后续变更需兼容方案。

## 如何核对目录

Java 8，使用当前已有 Maven 依赖：

```bash
./mvnw -Dtest=ApiInventoryContractTest test
./mvnw clean package
```

[ApiInventoryContractTest](../../src/test/java/io/github/frewily/campushub/controller/ApiInventoryContractTest.java) 自动扫描主源码产物中的 Controller（排除测试探针），注册到 standalone Spring MVC，将实际 method/path、handler 与方法级授权声明逐项对照 routes.json，再检查逐接口表格没有缺失、多写或重复。此前的门禁明确断言六条评论/回复/撤回映射；7F 新增管理员映射及目录同步由 7F 阶段验证覆盖。7G 门禁明确断言 45 个应用路由、13 个 Controller、原评论 6 条加管理员 2 条，其 4 项检查及本轮 26 项定向/360 项默认统计通过（默认有 4 项设计跳过）。该测试不发请求、不启用安全过滤链、不连接 DB/Redis、不启动 worker。

7H 门禁明确断言 46 个应用路由、13 个 Controller、原评论 6 条加管理员 3 条；本轮 40 项定向检查及 374 项默认统计通过（默认有 4 项设计跳过）。上述 7G 数字是历史记录，不用于外推 7H。

它不能自动核验文档中的字段语义、真实角色/归属执行、Service/Mapper 成功路径、数据库状态或管理面暴露；这些仍需源码审查及阶段明确范围内的 HTTP/隔离验收，不能把映射一致等同于 46 个完整端到端接口都已验收。7H 的接口、分页和迁移已在选定本轮范围内实现与验证，真实 MySQL 49 项与目标 Compose 25 组证据见[7H 阶段记录](../refactor/30-phase-7h-comment-audit-pages.md)。6E2 性能原始证据本阶段不重跑/改写。
