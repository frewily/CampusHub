# API endpoints（源码现状）

本目录按 `src/main/java` 中显式声明的业务 Controller 映射整理：共 39 条，来自 12 个 Controller。方法和路径按类级 `@RequestMapping` 与方法级映射合并；未把 Spring 自动支持的 HEAD/OPTIONS、框架 `/error` 或 Actuator 管理端计入。7B 增加两条一级评论 API；评论能力和边界见[阶段记录](../refactor/24-phase-7b-comments.md)。

## 通用约定

- 除图片二进制、健康探针和显式返回 `ResponseEntity` 的搜索异常外，业务成功通常返回 `Result`：`success=true`，可选 `data`。`Result` 是响应体类型，不决定 HTTP 状态；HTTP 状态来自 Controller 的 `ResponseEntity` 或异常处理。`Result.ok()` 的 `data` 为 null；配置启用了 Jackson `NON_NULL`，序列化时 null 字段可能不出现。业务异常由 `GlobalExceptionHandler` 转成对应 HTTP 状态和 `Result.fail`，不是所有失败都返回 200。常见映射包括参数错误 400、未认证 401、无权限 403、缺失资源 404、状态冲突 409、依赖不可用 503。
- 成功的 `data=null` 与资源缺失的 404 不等价：用户资料、用户简档以及空的关注动态流会成功但没有 data；门店详情、动态详情和不存在的订单会给 404。集合为空通常是成功的空数组。
- 登录令牌通过请求头 `authorization` 原样传递（服务端仅 trim 首尾空白），没有 `Bearer` scheme 解析。认证从 Redis 会话装载用户与 `USER`、`MERCHANT`、`ADMIN` 角色；没有独立的学生角色/认证类型。`USER` 是普通用户角色。缺失、无效或已失效 token 不建立认证上下文；受保护路由因此按未认证处理，公开路由仍可匿名访问，包括携带无效 token 的请求。
- `SecurityConfig` 对登录验证码和登录开放；指定 GET 查询与图片/健康路由公开；其余路由要求已认证。方法级资源策略再限制角色和所属资源。参与秒杀、订单读取/取消和签到的策略允许有效的 USER/MERCHANT 且身份不含 ADMIN；参与本身不要求商户店铺成员关系。创建/管理店铺或促销才由管理员或具有目标商户/店铺有效成员关系的商户执行。标注 `hasAnyRole('USER','MERCHANT','ADMIN')` 的接口确实包含 ADMIN。
- 标为 Entity 的返回值是当前代码直接返回持久化实体，不代表已统一到安全/稳定的 VO；专用 DTO/Response 只在对应条目明确标出。评论接口没有用户可控的作者、状态或计数字段映射。

## 用户与会话

Controller：[UserController.java](../../src/main/java/io/github/frewily/campushub/controller/UserController.java)；Service：[IUserService.java](../../src/main/java/io/github/frewily/campushub/service/IUserService.java)、[UserServiceImpl.java](../../src/main/java/io/github/frewily/campushub/service/impl/UserServiceImpl.java)、[UserInfoServiceImpl.java](../../src/main/java/io/github/frewily/campushub/service/impl/UserInfoServiceImpl.java)；模型：[LoginFormDTO.java](../../src/main/java/io/github/frewily/campushub/dto/LoginFormDTO.java)、[UserDTO.java](../../src/main/java/io/github/frewily/campushub/dto/UserDTO.java)、[UserInfoResponse.java](../../src/main/java/io/github/frewily/campushub/dto/response/UserInfoResponse.java)、[Result.java](../../src/main/java/io/github/frewily/campushub/dto/Result.java)；访问控制：[SecurityConfig.java](../../src/main/java/io/github/frewily/campushub/config/SecurityConfig.java)、[ResourceAuthorizationService.java](../../src/main/java/io/github/frewily/campushub/security/ResourceAuthorizationService.java)。

| Method | Path | Handler；认证与资源限制 | 请求与约束 | 成功响应 `data` / 缺失资源语义 |
|---|---|---|---|---|
| POST | `/user/code` | [`sendCode`](../../src/main/java/io/github/frewily/campushub/controller/UserController.java#L43)；公开 | Query `phone` 必填、非空且匹配手机号格式 | 字符串“发送验证码成功”；频率限制为 429 |
| POST | `/user/login` | [`login`](../../src/main/java/io/github/frewily/campushub/controller/UserController.java#L59)；公开 | JSON `LoginFormDTO`：`phone` 必填手机号，`code` 必填并匹配验证码格式；`password` 可省略，若提供须匹配密码格式。当前服务用验证码登录 | 登录 token 字符串；错误验证码/停用账户按认证错误，失败过多为 429 |
| POST | `/user/logout` | [`logout`](../../src/main/java/io/github/frewily/campushub/controller/UserController.java#L68)；需认证 | Header `authorization` 必须存在，传原始 token，不加 `Bearer` | `Result` 成功、无 data；删除会话 |
| GET | `/user/me` | [`me`](../../src/main/java/io/github/frewily/campushub/controller/UserController.java#L73)；需认证 | 无参数 | `UserDTO`：`id`、`nickName`、`icon` |
| GET | `/user/info/{id}` | [`info`](../../src/main/java/io/github/frewily/campushub/controller/UserController.java#L79)；需认证，无角色限制 | Path `id` 为正数 | 有资料时 `UserInfoResponse`；无记录时成功且 data 为 null，不是 404 |
| GET | `/user/{id}` | [`queryUserById`](../../src/main/java/io/github/frewily/campushub/controller/UserController.java#L87)；需认证，无角色限制 | Path `id` 为正数 | 有用户时复制为 `UserDTO`；无用户时成功且 data 为 null，不是 404 |
| POST | `/user/sign` | [`sign`](../../src/main/java/io/github/frewily/campushub/controller/UserController.java#L98)；需认证；`canParticipateAsUser` 允许 USER/MERCHANT、拒绝 ADMIN | 无参数 | 成功，无 data |
| GET | `/user/sign/count` | [`signCount`](../../src/main/java/io/github/frewily/campushub/controller/UserController.java#L104)；需认证；允许 USER/MERCHANT、拒绝 ADMIN | 无参数 | 当前月连续签到天数整数；无签到为 `0` |

## 门店与搜索

Controller：[ShopController.java](../../src/main/java/io/github/frewily/campushub/controller/ShopController.java)、[ShopSearchController.java](../../src/main/java/io/github/frewily/campushub/controller/ShopSearchController.java)；Service：[IShopService.java](../../src/main/java/io/github/frewily/campushub/service/IShopService.java)、[ShopServiceImpl.java](../../src/main/java/io/github/frewily/campushub/service/impl/ShopServiceImpl.java)、[ShopSearchService.java](../../src/main/java/io/github/frewily/campushub/service/ShopSearchService.java)；模型：[Shop.java](../../src/main/java/io/github/frewily/campushub/entity/Shop.java)、[ShopCreateRequest.java](../../src/main/java/io/github/frewily/campushub/dto/request/ShopCreateRequest.java)、[ShopUpdateRequest.java](../../src/main/java/io/github/frewily/campushub/dto/request/ShopUpdateRequest.java)、[ShopSearchRequest.java](../../src/main/java/io/github/frewily/campushub/dto/request/ShopSearchRequest.java)、[ShopSearchPage.java](../../src/main/java/io/github/frewily/campushub/dto/response/ShopSearchPage.java)、[ShopSearchItem.java](../../src/main/java/io/github/frewily/campushub/dto/response/ShopSearchItem.java)、[ApiModelMapper.java](../../src/main/java/io/github/frewily/campushub/dto/ApiModelMapper.java)。

| Method | Path | Handler；认证与资源限制 | 请求与约束 | 成功响应 `data` / 缺失资源语义 |
|---|---|---|---|---|
| GET | `/shop/{id}` | [`queryShopById`](../../src/main/java/io/github/frewily/campushub/controller/ShopController.java#L39)；公开 | Path `id` 为正数 | 直接返回 `Shop` Entity（含内部/归属字段）；不存在抛 404 |
| POST | `/shop` | [`saveShop`](../../src/main/java/io/github/frewily/campushub/controller/ShopController.java#L50)；需认证；管理员可创建；商户需有请求 `merchantId` 的有效成员关系 | JSON `ShopCreateRequest`：`name` 非空、≤128；`typeId` 必填正数；`merchantId` 可选正数；`images` 必填、≤1024；`area` ≤128；`address` 必填、≤255；`x`/`y` 必填且分别为 0..180 / 0..90；`avgPrice` 非负；`openHours` ≤32。未知 JSON 字段忽略；销量、评论、评分由服务置 0 | 新建 `Shop` 的数字 ID；请求 DTO 映射到 Entity，不接受客户端设置归属外字段 |
| PUT | `/shop` | [`updateShop`](../../src/main/java/io/github/frewily/campushub/controller/ShopController.java#L61)；需认证；管理员或该店铺有效成员 | JSON `ShopUpdateRequest`：`id` 必填正数；其余可选：`name` 非空白、≤128；`typeId` 正数；`images` 非空白、≤1024；`area` ≤128；`address` 非空白、≤255；坐标 `x` 0..180、`y` 0..90；`avgPrice` 非负；`openHours` ≤32。未知字段忽略；不支持改变归属或统计字段 | 成功无 data；目标门店不存在为 404，更新冲突为 409 |
| GET | `/shop/of/type` | [`queryShopByType`](../../src/main/java/io/github/frewily/campushub/controller/ShopController.java#L72)；公开 | Query `typeId` 必填正数；`current` 默认 1 且 ≥1；可选 `x` ∈[-180,180]、`y` ∈[-90,90]（单独提供一个时服务按无坐标查询） | `List<Shop>` Entity；有坐标时按 Redis GEO 5km 范围并附 `distance`；两条查询分支每页均使用继承的 `IService.DEFAULT_BATCH_SIZE=1000`（当前 MyBatis-Plus 3.4.3），不是其他列表的 10；无结果为空数组 |
| GET | `/shop/of/name` | [`queryShopByName`](../../src/main/java/io/github/frewily/campushub/controller/ShopController.java#L93)；公开 | Query `name` 可省略；`current` 默认 1 且 ≥1；每页最多 10 | `List<Shop>` Entity；空结果为空数组 |
| GET | `/shop/search` | [`search`](../../src/main/java/io/github/frewily/campushub/controller/ShopSearchController.java#L27)；公开 | Query 仅绑定白名单：`keyword` ≤80；`typeId` 正数；`minPrice`/`maxPrice` 非负且下限≤上限；`minScore` 0..50；`x`,`y` 必须成对且有限，范围经度[-180,180]、纬度[-90,90]；`radiusMeters` 1..50000；`sort` 为 `id`（默认）、`price_asc`、`price_desc`、`score_desc`、`distance`；无坐标时不可设半径或 distance 排序；`page` 默认1、1..500；`size` 默认10、1..50 | `ShopSearchPage {items: ShopSearchItem[], total, page, size, sort, hasNext}`。`items` 是公开读白名单模型，不是 Entity；无匹配时空数组、total=0。事务或数据访问失败映射 503 `SHOP_STATE_UNAVAILABLE` |

## 门店类型

Controller：[ShopTypeController.java](../../src/main/java/io/github/frewily/campushub/controller/ShopTypeController.java)；Service：[IShopTypeService.java](../../src/main/java/io/github/frewily/campushub/service/IShopTypeService.java)、[ShopTypeServiceImpl.java](../../src/main/java/io/github/frewily/campushub/service/impl/ShopTypeServiceImpl.java)；模型：[ShopType.java](../../src/main/java/io/github/frewily/campushub/entity/ShopType.java)。

| Method | Path | Handler；认证与资源限制 | 请求与约束 | 成功响应 `data` / 缺失资源语义 |
|---|---|---|---|---|
| GET | `/shop-type/list` | [`queryTypeList`](../../src/main/java/io/github/frewily/campushub/controller/ShopTypeController.java#L21)；公开 | 无参数 | `List<ShopType>` Entity（`createTime`、`updateTime` 被 `@JsonIgnore`）；按 sort 升序；空列表为空数组 |

## 优惠券与订单

Controller：[VoucherController.java](../../src/main/java/io/github/frewily/campushub/controller/VoucherController.java)、[VoucherOrderController.java](../../src/main/java/io/github/frewily/campushub/controller/VoucherOrderController.java)；Service：[IVoucherService.java](../../src/main/java/io/github/frewily/campushub/service/IVoucherService.java)、[VoucherServiceImpl.java](../../src/main/java/io/github/frewily/campushub/service/impl/VoucherServiceImpl.java)、[FlashSaleAdmissionService.java](../../src/main/java/io/github/frewily/campushub/service/FlashSaleAdmissionService.java)、[OrderLifecycleService.java](../../src/main/java/io/github/frewily/campushub/service/OrderLifecycleService.java)；模型：[Voucher.java](../../src/main/java/io/github/frewily/campushub/entity/Voucher.java)、[VoucherCreateRequest.java](../../src/main/java/io/github/frewily/campushub/dto/request/VoucherCreateRequest.java)、[FlashSaleCreateRequest.java](../../src/main/java/io/github/frewily/campushub/dto/request/FlashSaleCreateRequest.java)、[OrderAcceptanceResult.java](../../src/main/java/io/github/frewily/campushub/dto/response/OrderAcceptanceResult.java)、[OrderStatusResponse.java](../../src/main/java/io/github/frewily/campushub/dto/response/OrderStatusResponse.java)、[ApiModelMapper.java](../../src/main/java/io/github/frewily/campushub/dto/ApiModelMapper.java)。

| Method | Path | Handler；认证与资源限制 | 请求与约束 | 成功响应 `data` / 缺失资源语义 |
|---|---|---|---|---|
| POST | `/voucher` | [`addVoucher`](../../src/main/java/io/github/frewily/campushub/controller/VoucherController.java#L34)；需认证；管理员或该店铺有效商户成员 | JSON `VoucherCreateRequest`：`shopId` 必填正数；`title` 必填、≤255；`subTitle` ≤255；`rules` ≤1024；`payValue` 必填非负；`actualValue` 必填正数；未知字段忽略 | 返回 `Voucher` Entity 的数字 ID；服务映射为普通券 `type=0,status=1` |
| POST | `/voucher/seckill` | [`addSeckillVoucher`](../../src/main/java/io/github/frewily/campushub/controller/VoucherController.java#L45)；需认证；管理员或该店铺有效商户成员 | JSON 为上述公共券字段加 `stock` 必填正数、`beginTime`/`endTime` 必填 `LocalDateTime` 且结束晚于开始；未知字段忽略 | 成功 `data` 为数字券 ID；请求先映射到 `Voucher` Entity，活动库存与时间另写活动记录并在提交后发布 |
| GET | `/voucher/list/{shopId}` | [`queryVoucherOfShop`](../../src/main/java/io/github/frewily/campushub/controller/VoucherController.java#L57)；公开 | Path `shopId` 正数 | `List<Voucher>` Entity；无券为空数组 |
| POST | `/voucher-order/seckill/{id}` | [`seckillVoucher`](../../src/main/java/io/github/frewily/campushub/controller/VoucherOrderController.java#L32)；需认证；仅 USER/MERCHANT 且账号 ACTIVE；ADMIN 明确拒绝 | Path `id`（活动券 ID）正数；无 body/query | `OrderAcceptanceResult`，继承 `Result`，成功 `data` 是数值 orderId，另含字符串 `orderId`、`acceptanceStatus=ACCEPTED`、`replayed`。ACCEPTED 表示 Redis 接受入队，不表示 MySQL 已落库/已支付；不存在活动是 404，业务拒绝按各自 409/403/503 |
| GET | `/voucher-order/{id}` | [`queryMine`](../../src/main/java/io/github/frewily/campushub/controller/VoucherOrderController.java#L39)；需认证；仅 USER/MERCHANT，且只能查当前用户本人并校验活动 ID；ADMIN 拒绝 | Path `id` 为订单正数；Query `voucherId` 必填正数；无 body | `OrderStatusResponse`：字符串 `orderId`、`voucherId`、状态、取消补偿状态及时间。已接受未落库显示 `ACCEPTED` 或 `REQUIRES_REVIEW`；从无权属订单/受理记录时 404 |
| POST | `/voucher-order/{id}/cancel` | [`cancelMine`](../../src/main/java/io/github/frewily/campushub/controller/VoucherOrderController.java#L46)；与订单读取相同的本人资源限制，ADMIN 拒绝 | Path `id` 订单正数；Query `voucherId` 必填正数；无 body | `OrderStatusResponse`；仅未支付订单可取消。尚未落库的已受理订单或不可取消状态为 409；订单不存在为 404。接口成功只表示取消事务完成，不表示异步库存补偿已完成 |

## 动态

Controller：[BlogController.java](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java)；Service：[IBlogService.java](../../src/main/java/io/github/frewily/campushub/service/IBlogService.java)、[BlogServiceImpl.java](../../src/main/java/io/github/frewily/campushub/service/impl/BlogServiceImpl.java)；模型：[Blog.java](../../src/main/java/io/github/frewily/campushub/entity/Blog.java)、[BlogCreateRequest.java](../../src/main/java/io/github/frewily/campushub/dto/request/BlogCreateRequest.java)、[ScrollResult.java](../../src/main/java/io/github/frewily/campushub/dto/ScrollResult.java)、[UserDTO.java](../../src/main/java/io/github/frewily/campushub/dto/UserDTO.java)、[ApiModelMapper.java](../../src/main/java/io/github/frewily/campushub/dto/ApiModelMapper.java)。

| Method | Path | Handler；认证与资源限制 | 请求与约束 | 成功响应 `data` / 缺失资源语义 |
|---|---|---|---|---|
| POST | `/blog` | [`saveBlog`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java#L33)；需认证；允许 USER、MERCHANT、ADMIN | JSON `BlogCreateRequest`：`shopId` 可省略/null（校园动态），提供则须为正数；`title` 非空、≤255；`images`、`content` 非空且各≤2048；未知字段忽略 | 返回新 Blog ID；发布者从会话取得，liked/comments 初始化为 0；读取无关联动态时 shopId 可因 NON_NULL 省略。7A 需先应用 V006；关联不代表商户身份，未新增门店存在性验证 |
| PUT | `/blog/like/{id}` | [`likeBlog`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java#L39)；需认证；允许 USER、MERCHANT、ADMIN | Path `id` 动态 ID 正数；无 body | 成功无 data；点赞/取消点赞切换。服务未对不存在的 ID 单独报 404 |
| GET | `/blog/of/me` | [`queryMyBlog`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java#L44)；需认证 | Query `current` 默认 1 且 ≥1 | `List<Blog>` Entity，按当前用户分页（每页最多 10）；无记录为空数组 |
| GET | `/blog/hot` | [`queryHotBlog`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java#L54)；公开 | Query `current` 默认 1 且 ≥1 | `List<Blog>` Entity，按 liked 倒序（每页最多 10），附用户昵称/头像和当前用户点赞标记；匿名标记为 false；无记录为空数组 |
| GET | `/blog/{id}` | [`queryBlogById`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java#L60)；需认证 | Path `id` 正数 | `Blog` Entity，附用户昵称/头像和点赞标记；动态不存在抛 404 |
| GET | `/blog/likes/{id}` | [`queryBlogLikes`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java#L65)；需认证 | Path `id` 正数 | 最多 5 个点赞用户的 `List<UserDTO>`；无点赞为空数组 |
| GET | `/blog/of/user` | [`queryBlogByUserId`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java#L70)；需认证 | Query `current` 默认 1 且 ≥1；`id` 用户 ID 必填正数 | `List<Blog>` Entity，每页最多 10；无记录为空数组 |
| GET | `/blog/of/follow` | [`queryBlogOfFollow`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java#L81)；需认证 | Query `lastId` 必填正数（滚动时间上界）；`offset` 默认 0 且 ≥0 | 有记录时 `ScrollResult {list: Blog[], minTime, offset}`，当前每批最多 2 条；无动态时 `Result.ok()`，即成功且 data 为 null，不是 404 |

## 动态评论（Phase 7B）

Controller：[BlogCommentsController.java](../../src/main/java/io/github/frewily/campushub/controller/BlogCommentsController.java)；Service：[IBlogCommentsService.java](../../src/main/java/io/github/frewily/campushub/service/IBlogCommentsService.java)、[BlogCommentsServiceImpl.java](../../src/main/java/io/github/frewily/campushub/service/impl/BlogCommentsServiceImpl.java)；模型：[BlogCommentCreateRequest.java](../../src/main/java/io/github/frewily/campushub/dto/request/BlogCommentCreateRequest.java)、[BlogCommentPageRequest.java](../../src/main/java/io/github/frewily/campushub/dto/request/BlogCommentPageRequest.java)、[BlogCommentItem.java](../../src/main/java/io/github/frewily/campushub/dto/response/BlogCommentItem.java)、[BlogCommentPage.java](../../src/main/java/io/github/frewily/campushub/dto/response/BlogCommentPage.java)。

| Method | Path | Handler；认证与资源限制 | 请求与约束 | 成功响应 `data` / 缺失资源语义 |
|---|---|---|---|---|
| POST | `/blog-comments` | [`createComment`](../../src/main/java/io/github/frewily/campushub/controller/BlogCommentsController.java)；需认证；方法 guard 为 `hasAnyRole('USER', 'MERCHANT', 'ADMIN')` | JSON：`blogId` 必填正数，`content` 非空白且最多 255 字符；未知字段忽略，包括客户端传入的 author/userId、id、status、parentId、answerId、liked/count 等字段 | 创建一级评论，作者来自会话；parentId/answerId/liked/status 由服务设为 0，并在同一事务中递增动态 comments（空值按 0 处理）。成功返回仅含字符串 `id`、`blogId`、`userId`、`content` 和 `createTime` 的 `BlogCommentItem`；动态不存在为 404，SQL 异常为通用 500，写入/计数行数不符合预期为 422 |
| GET | `/blog-comments/of/blog/{blogId}` | [`listComments`](../../src/main/java/io/github/frewily/campushub/controller/BlogCommentsController.java)；需认证（无方法级 guard，由全局 SecurityConfig 保护） | Path `blogId` 必填正数；Query `beforeId` 可选正数；`size` 为 1..50，默认 20。按 ID 倒序读取，`beforeId` 表示严格小于的游标 | `BlogCommentPage {items, hasNext, nextBeforeId}`；`items` 每项仅有字符串 `id`、`blogId`、`userId`、`content`、`createTime`。仅 status=0、parentId=0、answerId=0 的一级可见评论；举报(1)、隐藏(2)、null 状态及回复不返回。`nextBeforeId` 在 `hasNext=true` 时为下一页游标字符串，否则 null（NON_NULL 配置下省略）；动态不存在为 404 |

本阶段不支持回复、删除、审核/状态变更、通知、请求幂等键或历史 comments 计数回填；不据此宣称完整评论评价系统。V007 增加 `(blog_id,parent_id,answer_id,status,id)` 游标索引，细节与兼容边界见[阶段记录](../refactor/24-phase-7b-comments.md)和[游标学习笔记](../learning/first-level-comments-and-cursors.md)。

## 关注

Controller：[FollowController.java](../../src/main/java/io/github/frewily/campushub/controller/FollowController.java)；Service：[IFollowService.java](../../src/main/java/io/github/frewily/campushub/service/IFollowService.java)、[FollowServiceImpl.java](../../src/main/java/io/github/frewily/campushub/service/impl/FollowServiceImpl.java)；模型：[Follow.java](../../src/main/java/io/github/frewily/campushub/entity/Follow.java)、[UserDTO.java](../../src/main/java/io/github/frewily/campushub/dto/UserDTO.java)。

| Method | Path | Handler；认证与资源限制 | 请求与约束 | 成功响应 `data` / 缺失资源语义 |
|---|---|---|---|---|
| PUT | `/follow/{id}/{isFollow}` | [`follow`](../../src/main/java/io/github/frewily/campushub/controller/FollowController.java#L24)；需认证；允许 USER、MERCHANT、ADMIN | Path `id` 目标用户 ID 正数；`isFollow` 转为 Boolean；无 body | 成功无 data；不能关注自己时 409；重复关注保持成功 |
| GET | `/follow/or/not/{id}` | [`isFollow`](../../src/main/java/io/github/frewily/campushub/controller/FollowController.java#L31)；需认证 | Path `id` 正数 | Boolean；当前用户是否关注目标用户 |
| GET | `/follow/common/{id}` | [`common`](../../src/main/java/io/github/frewily/campushub/controller/FollowController.java#L36)；需认证 | Path `id` 目标用户 ID 为正数 | 共同关注的 `List<UserDTO>`；任一关注集合为空或交集为空时返回空数组 |

## 图片上传与读取

Controller：[UploadController.java](../../src/main/java/io/github/frewily/campushub/controller/UploadController.java)、[ImageController.java](../../src/main/java/io/github/frewily/campushub/controller/ImageController.java)；存储：[ImageStorage.java](../../src/main/java/io/github/frewily/campushub/storage/ImageStorage.java)、[LocalImageStorage.java](../../src/main/java/io/github/frewily/campushub/storage/LocalImageStorage.java)、[application.yaml](../../src/main/resources/application.yaml)。

| Method | Path | Handler；认证与资源限制 | 请求与约束 | 成功响应 `data` / 缺失资源语义 |
|---|---|---|---|---|
| POST | `/upload/blog` | [`uploadImage`](../../src/main/java/io/github/frewily/campushub/controller/UploadController.java#L22)；需认证；允许 USER、MERCHANT、ADMIN | multipart/form-data 字段 `file` 必填；应用限制单文件 2 MiB、请求 3 MiB；存储仅接受有效 JPEG/PNG，输入及重新编码后的文件均≤2 MiB，解码图像≤16,000,000 像素（不是解码内存≤2 MiB） | `data` 是 `/blogs/{0..15}/{0..15}/{uuid}.jpg\|png` 图片路径；无效图片 400，存储不可用 503 |
| GET | `/upload/blog/delete` | [`deleteBlogImg`](../../src/main/java/io/github/frewily/campushub/controller/UploadController.java#L28)；需认证且必须 ADMIN | Query `name` 必填非空；存储还要求路径严格匹配受控图片名 | 成功无 data；删除单个精确文件。此路由是遗留的 GET 写操作，当前行为是删除，不能当成读取接口 |
| GET | `/imgs/blogs/{first}/{second}/{filename}` | [`image`](../../src/main/java/io/github/frewily/campushub/controller/ImageController.java#L12)；公开 | Path 三段；存储层只接受目录段 0..15 和受控 UUID 文件名、jpg/png 后缀 | 成功为原始 `byte[]`，HTTP 200，PNG/JPEG Content-Type、`nosniff`、inline disposition，不使用 `Result` 包装；文件不存在抛 NOT_FOUND，由全局处理器返回 404 和 `Result.fail` JSON 错误体 |

## 健康探针（业务 Controller）

Controller：[HealthController.java](../../src/main/java/io/github/frewily/campushub/controller/HealthController.java)；依赖检查：[ReadinessService.java](../../src/main/java/io/github/frewily/campushub/service/ReadinessService.java)。

| Method | Path | Handler；认证与资源限制 | 请求与约束 | 响应 |
|---|---|---|---|---|
| GET | `/health/live` | [`live`](../../src/main/java/io/github/frewily/campushub/controller/HealthController.java#L13)；公开 | 无参数 | 原始 JSON `{"status":"UP"}` |
| GET | `/health/ready` | [`ready`](../../src/main/java/io/github/frewily/campushub/controller/HealthController.java#L14)；公开 | 无参数 | 原始 JSON status；MySQL `SELECT 1` 与 Redis `PING` 都成功为 HTTP 200/UP，否则 HTTP 503/DOWN |

## 独立管理端（不计入 39 条）

`SecurityConfig.managementSecurityFilterChain` 与 `application.yaml` 暴露两个 Actuator GET：`GET /actuator/health`、`GET /actuator/prometheus`。它们不属于业务 Controller 清单；管理监听地址默认 `127.0.0.1:8082`（端口可由配置替换），管理链对这两个 GET 公开并拒绝其他 Actuator 请求。Actuator 路由由 Spring Boot 管理端框架提供。
