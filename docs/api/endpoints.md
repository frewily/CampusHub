# API endpoints（源码现状）

本目录按 `src/main/java` 中显式声明的业务 Controller 映射整理：共51条，来自13个Controller；不计框架隐式HEAD/OPTIONS、`/error`或Actuator管理端。评论相关为6条评论/回复/撤回和6条ADMIN隐藏/审计路由。7O增加直接回复隐藏，7P/7Q分别增加单条/按动态分页读取回复审计；阶段证据与兼容边界见各自记录，历史数量不当作当前数量。

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
| POST | `/blog` | [`saveBlog`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java)；需认证；允许 USER、MERCHANT、ADMIN；匿名、停用账号或无有效角色为 401 | JSON `BlogCreateRequest`：`shopId` 可省略/null 或正数；`title` 非空白且≤255，`content` 非空白且≤2048。无关联门店时 `images` 可省略/null/空串/`String.trim()` 后为空白，映射为非 null `""` 再保存；正数 `shopId` 时仍要求 `images` 非空白。原始 `images` 长度≤2048 个 Java UTF-16 单元，在归一化前校验，超限即拒绝；非空图片原串保留。未知字段忽略 | 返回新 Blog ID；作者从会话取得，客户端 id/userId/计数/时间等未知字段忽略；liked/comments 初始化为 0。空图片以 `""` 正常序列化，不因 NON_NULL 省略。旧带图/关联门店请求兼容；关联不证明商户身份，未新增门店存在性验证。语义见 7I 阶段记录，本轮实现与选定验证完成；不外推生产或客户端 UI 验收 |
| PUT | `/blog/{id}` | [`editBlog`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java)；需认证；允许 USER、MERCHANT、ADMIN，但三种角色均只能编辑存量动态作者本人；ADMIN 无覆盖权限。匿名、停用账号、未知/无角色身份为 401；他人动态为 403；动态不存在为 404 | Path `id` 必须是正 Java Long。JSON 完整替换 `title`（非空白且≤255）、`images`（可 null，原始长度≤2048；未知字段忽略）、`content`（非空白且≤2048）；未知 JSON 字段及客户端 id/userId/shopId/liked/comments/time 等不参与更新。门店关联从加锁读取的存量行取得且不可变：关联门店动态仍要求 `images` 非空白；无门店动态的 null/空串/trim 空白归一为非 null `""`，合法非空原串原样保留 | 成功 `Result.ok()`，无 data。参数错误或关联动态无图为 400；更新影响行数不是 1 为 422；数据访问异常映射为不带 cause 的通用 500。事务内对存量行执行当前读 `SELECT ... FOR UPDATE`，再只更新 title/images/content；5 秒语句超时不代表 SLA。字段值完全相同则不发 UPDATE，也不刷新 updateTime。详见[7J 阶段记录](../refactor/32-phase-7j-post-author-edit.md)；实现与选定本轮验证完成，不外推全部端点或生产验收 |
| DELETE | `/blog/{id}` | [`withdrawBlog`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java)；需认证；USER/MERCHANT/ADMIN 均仅作者本人，ADMIN 无覆盖权限；他人 403，缺失 404 | Path `id` 正 Java Long；不要求 body，作者从会话取，不接受 body 身份 | 成功无 data；当前行锁下仅 withdrawn 0→1，重复本人成功且不写。参数错误 400，非1影响行数 422，数据异常无 cause 通用500。原文、计数、评论、图片和 Redis 保留；可见性与回退要求见[7K 记录](../refactor/33-phase-7k-post-author-withdrawal.md) |
| PUT | `/blog/like/{id}` | [`likeBlog`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java)；需认证；允许 USER、MERCHANT、ADMIN | Path `id` 动态 ID 正数；无 body | 成功无 data；点赞/取消点赞切换。7K 对不存在/已撤回 ID 报404；先锁可见动态再访问 Redis，但 DB/Redis 仍非原子 |
| GET | `/blog/of/me` | [`queryMyBlog`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java)；需认证 | Query `current` 默认 1 且 ≥1 | `List<Blog>` Entity，按当前用户分页（每页最多 10）；无记录为空数组 |
| GET | `/blog/hot` | [`queryHotBlog`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java)；公开 | Query `current` 默认 1 且 ≥1 | `List<Blog>` Entity，按 liked 倒序（每页最多 10），附用户昵称/头像和当前用户点赞标记；匿名标记为 false；无记录为空数组 |
| GET | `/blog/{id}` | [`queryBlogById`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java)；需认证 | Path `id` 正数 | `Blog` Entity，附用户昵称/头像和点赞标记；动态不存在抛 404 |
| GET | `/blog/likes/{id}` | [`queryBlogLikes`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java)；需认证 | Path `id` 正数 | 最多 5 个点赞用户的 `List<UserDTO>`；无点赞为空数组 |
| GET | `/blog/of/user` | [`queryBlogByUserId`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java)；需认证 | Query `current` 默认 1 且 ≥1；`id` 用户 ID 必填正数 | `List<Blog>` Entity，每页最多 10；无记录为空数组 |
| GET | `/blog/of/follow` | [`queryBlogOfFollow`](../../src/main/java/io/github/frewily/campushub/controller/BlogController.java)；需认证；从当前会话用户的Feed取成员 | Query `lastId` 必填正Long（包含边界的时间上界）；`offset` 非负Integer，默认0 | 原Redis批次非空返回 `ScrollResult {list: Blog[], minTime, offset}`，每批最多2个成员。同时间组offset累计，进入较早组只算本页末尾同组数；无法表示的累加为422。DB过滤后list可空，仍须继续用返回游标；Redis无原成员返回无data成功，不是404。静态ZSet及并发变动边界见[7L记录](../refactor/34-phase-7l-follow-feed-cursor.md) |

7K 可见性：详情、点赞列表、编辑、新点赞对已撤回动态返回 404；热门、本人、用户、Feed 列表自动过滤。Feed 保留旧 ID/分数，游标按原 Redis 批次推进，过滤后可为空页且不补齐；此前已开始的读取不保证被撤回追溯取消。现有点赞列表的缺失动态行为也调整为404。内部 withdrawn 字段不暴露在 Blog JSON，不支持恢复。

发布补充（7M）：POST/blog的DTO/角色/200+ID不变，动态与本次DB关注快照的固定分数投递意图同事务。提交后快速尝试最多32位收件人，后台默认每批32条恢复；成功不承诺即时全部Feed可见。意图插入异常整单回滚并通用500，非1影响行数422；提交后普通投递故障保留待办，不改报发布失败。没有客户端幂等键，断连/未知提交结果后重复POST仍可产生新动态。认证仍依赖Redis；完整边界与迁移见[7M](../refactor/35-phase-7m-blog-feed-delivery.md)。

发布请求编号（7N）：可选单值 `Idempotency-Key`，应用值须完整匹配 `[A-Za-z0-9_-]{1,64}`、大小写敏感；空/非法/多值400，HTTP连接器外侧空白归一不在字节级保证内。不带编号仍用7M旧路径。带编号时每次先校验当前身份及原DTO，同用户/编号/归一化创建内容返回首次提交ID，不再写动态/受众/投递；变化内容409。失败整单回滚，SQL异常无cause通用500、非1占位/完成422。账本与动态/Feed意图同事务，记录长期保留；原动态编辑/撤回后重试只返回原ID，不恢复内容/可见性。正文/编号不回显；无新增响应字段，200+数字ID形状不变。V012、原编号重试策略、无编号/换编号仍可重复及回退要求见[7N](../refactor/36-phase-7n-blog-publication-keys.md)。

## 动态评论与直接回复（Phase 7B/7C/7D/7E）

Phase 7K：动态撤回后，新请求的一级评论/回复列表与创建均为 404；已有评论/回复作者仍能独立撤回，管理员隐藏及审计查询仍可用，不级联改写或删除历史行。

Controller：[BlogCommentsController.java](../../src/main/java/io/github/frewily/campushub/controller/BlogCommentsController.java)；Service：[IBlogCommentsService.java](../../src/main/java/io/github/frewily/campushub/service/IBlogCommentsService.java)、[BlogCommentsServiceImpl.java](../../src/main/java/io/github/frewily/campushub/service/impl/BlogCommentsServiceImpl.java)；模型：[BlogCommentCreateRequest.java](../../src/main/java/io/github/frewily/campushub/dto/request/BlogCommentCreateRequest.java)、[BlogCommentPageRequest.java](../../src/main/java/io/github/frewily/campushub/dto/request/BlogCommentPageRequest.java)、[BlogCommentItem.java](../../src/main/java/io/github/frewily/campushub/dto/response/BlogCommentItem.java)、[BlogCommentPage.java](../../src/main/java/io/github/frewily/campushub/dto/response/BlogCommentPage.java)。

| Method | Path | Handler；认证与资源限制 | 请求与约束 | 成功响应 `data` / 缺失资源语义 |
|---|---|---|---|---|
| POST | `/blog-comments` | [`createComment`](../../src/main/java/io/github/frewily/campushub/controller/BlogCommentsController.java)；需认证；方法 guard 为 `hasAnyRole('USER', 'MERCHANT', 'ADMIN')` | JSON：`blogId` 必填正数，`content` 非空白且最多 255 字符；未知字段忽略，包括客户端传入的 author/userId、id、status、parentId、answerId、liked/count 等字段 | 创建一级评论，作者来自会话；parentId/answerId/liked/status 由服务设为 0，并在同一事务中递增动态 comments（空值按 0 处理）。成功返回仅含字符串 `id`、`blogId`、`userId`、`content` 和 `createTime` 的 `BlogCommentItem`；动态不存在为 404，SQL 异常为通用 500，写入/计数行数不符合预期为 422 |
| GET | `/blog-comments/of/blog/{blogId}` | [`listComments`](../../src/main/java/io/github/frewily/campushub/controller/BlogCommentsController.java)；需认证（无方法级 guard，由全局 SecurityConfig 保护） | Path `blogId` 必填正数；Query `beforeId` 可选正数；`size` 为 1..50，默认 20。按 ID 倒序读取，`beforeId` 表示严格小于的游标 | `BlogCommentPage {items, hasNext, nextBeforeId}`；`items` 每项仅有字符串 `id`、`blogId`、`userId`、`content`、`createTime`。仅 status=0、parentId=0、answerId=0 的一级可见评论；举报(1)、隐藏(2)、null 状态及回复不返回。`nextBeforeId` 在 `hasNext=true` 时为下一页游标字符串，否则 null（NON_NULL 配置下省略）；动态不存在为 404 |

| POST | `/blog-comments/{commentId}/replies` | [`createReply`](../../src/main/java/io/github/frewily/campushub/controller/BlogCommentsController.java)；需认证；允许 USER、MERCHANT、ADMIN | Path `commentId` 为正数且必须指向 status=0、parentId=0、answerId=0 的一级评论；JSON [BlogCommentReplyRequest](../../src/main/java/io/github/frewily/campushub/dto/request/BlogCommentReplyRequest.java) 仅接收非空白、≤255 的 content，其他字段忽略 | `BlogCommentItem`（同上字符串 ID 白名单）。blogId 从父评论派生、作者从会话取，parentId/answerId 固定为路径 commentId、liked/status=0。动态和父评论按固定顺序锁定，当前读复核可见性，插入/计数/回读同事务；父评论不存在、非正常一级评论或动态缺失均 404，SQL 异常通用 500，写入行数异常 422 |
| GET | `/blog-comments/{commentId}/replies` | [`listReplies`](../../src/main/java/io/github/frewily/campushub/controller/BlogCommentsController.java)；需认证（全局 SecurityConfig） | Path commentId 必须正数；beforeId 可选正数；size 默认 20、范围 1..50；仅可见正常一级评论的直接回复 | `BlogCommentPage`（同上），派生 blogId，过滤 parentId=answerId=commentId 且 status=0，ID 降序、严格小于游标。动态或父评论不可用/非一级均 404；无回复为 items=[]、hasNext=false，无 nextBeforeId；只读 RR 单请求快照，不承诺跨页快照 |

| DELETE | `/blog-comments/replies/{replyId}` | [`withdrawReply`](../../src/main/java/io/github/frewily/campushub/controller/BlogCommentsController.java)；需认证；允许 USER、MERCHANT、ADMIN，但均仅可操作本人回复，没有管理员覆盖权限 | Path replyId 为 Java Long 正数；无 body。目标须作者匹配、parentId>0 且 answerId=parentId、状态为 0 或 3，所属动态存在；不要求父评论存在或可见 | HTTP 200、`{"success":true}`，无 data。0→3（作者撤回）与动态 comments 递减同事务，NULL/0 下限为 0；3 重复成功不重复写入/扣计数。保留原文及关系，不物理删除。非本人、一级评论、不符合关系/状态、缺失目标/动态均 404；参数 400、无效会话 401、SQL 异常通用 500、影响行数异常 422 |

| DELETE | `/blog-comments/{commentId}` | [`withdrawComment`](../../src/main/java/io/github/frewily/campushub/controller/BlogCommentsController.java)；需认证；允许 USER、MERCHANT、ADMIN，但均仅可操作本人一级评论，没有商户/管理员覆盖权限 | Path commentId 为 Java Long 正数；无 body。目标须作者匹配、parentId=answerId=0、状态为 0 或 3，所属动态存在；直接回复不能用此入口撤回 | HTTP 200、`{"success":true}`，无 data。0→3 与动态 comments 递减同事务，NULL/0 下限 0；3 重复不写入/扣计数。仅根评论减一，保留原文及所有回复行/状态；随后新请求的回复读取/创建为 404，回复作者仍可用上述回复撤回入口。非本人（含已撤回）、关系/状态不符、目标/动态缺失均 404；参数 400、无效会话 401、SQL 异常通用 500、影响行数异常 422 |

作者撤回使用状态 3，管理员隐藏使用状态 2，均只扣目标对象一次，保留原文和关系；comments 是维护的派生计数，不保证等于当前可见数量。并发中的只读快照可读到变更前数据。7C–7E 没有新结构迁移，7F 增加 V008 根评论审计表；7O 增加 V013 直接回复审计表。当前不支持多级回复、级联/物理删除、恢复、举报工作流、通知、创建请求幂等键或历史计数回填。说明见[7E 记录](../refactor/27-phase-7e-comment-withdrawal.md)、[7O 记录](../refactor/37-phase-7o-direct-reply-moderation.md)、[一级评论撤回笔记](../learning/first-level-comment-withdrawal.md)、[回复撤回笔记](../learning/direct-reply-withdrawal.md)、[一级评论笔记](../learning/first-level-comments-and-cursors.md)和[直接回复笔记](../learning/direct-comment-replies.md)。

## 一级评论管理员隐藏（Phase 7F，业务应用端）

Controller：[AdminBlogCommentsController.java](../../src/main/java/io/github/frewily/campushub/controller/AdminBlogCommentsController.java)；请求：[BlogCommentHideRequest.java](../../src/main/java/io/github/frewily/campushub/dto/request/BlogCommentHideRequest.java)；复用上述评论服务/Mapper；[V008](../../src/main/resources/db/migration/V008__add_blog_comment_hide_audit.sql)新增审核记录表。此路由在应用端口；不是独立 Actuator 管理面。

| Method | Path | Handler；认证与资源限制 | 请求与约束 | 成功响应 `data` / 缺失资源语义 |
|---|---|---|---|---|
| POST | `/admin/blog-comments/{commentId}/hide` | [`hideComment`](../../src/main/java/io/github/frewily/campushub/controller/AdminBlogCommentsController.java)；需认证；方法 guard 为 `hasRole('ADMIN')`，可隐藏他人或本人正常一级评论，USER/MERCHANT 即使是作者也不允许 | Path commentId 为 Java Long 正数；JSON reason 非空白且最多 255 个 Java 字符单元，未知字段忽略。动态存在、parentId=answerId=0、状态 0；已隐藏 2 仅有同动态 0→2 审核记录时允许重复成功 | HTTP 200、`{"success":true}`，无 data。0→2、comments 减一（NULL/0 下限 0）、首次 operator/reason/from/to/时间审核记录同事务；保留原文和回复。重复不扣数、不改首次记录；未审计的旧 2、1/3/NULL、回复、关系不符或目标/动态缺失 404。参数 400、无效会话 401、非管理员 403、数据库异常通用 500、影响行数异常 422 |

先锁动态，再当前读锁根和重复分支的审核记录，避免旧 RR 快照误判并发重复。首次成功原因不可覆盖，审核记录不会由本用例更新/删除；不承诺防数据库管理员篡改。根隐藏后新请求回复列表/创建为 404，回复作者仍可独立撤回；根作者不能把隐藏改成作者撤回。作者撤回先完成则管理员隐藏为 404。7F 本身没有审核读取接口，7G 的单条查询与 7H 的按动态分页见后续两节；仍未提供全局审核列表、恢复、举报工作流或完整管理后台，见[7F 记录](../refactor/28-phase-7f-comment-moderation.md)和[审核笔记](../learning/first-level-comment-moderation.md)。

## 直接回复管理员隐藏（Phase 7O，业务应用端）

源码与选定本机验证已完成，最终提交与同步状态以交付对话为准。HTTP mock的初始404仅证明当时路由缺失；真实MySQL与Compose证据、完整边界见[7O阶段记录](../refactor/37-phase-7o-direct-reply-moderation.md)及[学习笔记](../learning/direct-reply-moderation.md)。

Controller：[AdminBlogCommentsController.java](../../src/main/java/io/github/frewily/campushub/controller/AdminBlogCommentsController.java)；请求复用：[BlogCommentHideRequest.java](../../src/main/java/io/github/frewily/campushub/dto/request/BlogCommentHideRequest.java)；独立审计迁移：[V013](../../src/main/resources/db/migration/V013__add_blog_reply_hide_audit.sql)。该审计表不并入 V008 根评论审计读取接口。

| Method | Path | Handler；认证与资源限制 | 请求与约束 | 成功响应 `data` / 缺失资源语义 |
|---|---|---|---|---|
| POST | `/admin/blog-comments/replies/{replyId}/hide` | [`hideReply`](../../src/main/java/io/github/frewily/campushub/controller/AdminBlogCommentsController.java)；需认证；`hasRole('ADMIN')` | Path `replyId` 为正 Java Long；JSON 复用隐藏请求，仅 `reason` 生效，非空白且最多255个Java UTF-16单元；操作人来自会话 | HTTP 200、`{"success":true}`，无 data。合法直接回复从0→2，动态comments按零下限减一，并写一条V013审计，在同一事务完成。有效重复仅凭匹配的V013用途审计返回200，不覆盖首次操作者/原因、不再扣数；没有匹配审计的旧status=2返回404。参数错误400、未认证401、非ADMIN 403、目标/动态缺失或关系/状态非法404、写入行数不符422、数据访问异常通用500 |

候选查询后依次锁动态、同动态规范根评论、目标直接回复，并用锁定当前读复核；父根是否隐藏或撤回不阻止本操作。仅接受 `parent_id>0`、`answer_id=parent_id` 且状态0/2的直接回复，不接受多级关系。隐藏/撤回的根以及已撤回的动态下，只要动态、规范根与目标回复仍存在且回复自身状态/关系合法，仍可隐藏。回复正文、liked、关系、根和兄弟回复保留，不级联改状态；动态 `comments` 是写入维护的派生数，不等于“当前可见评论数”。

隐藏后的status=2回复不能被作者撤回。V013 使用 `UNIQUE(reply_id)` 独立记录 reply/blog/root/operator/reason/from/to/time；旧V008单条/分页根评论读取不混入回复审计，7O当时未增加审计读取路由，7P已补回复单条读取。7O当时新库顺序为V001…V013，当前7Q到V014；已有库先备份、核对结构并审查V013再升级。`CREATE TABLE IF NOT EXISTS`不修复同名错表，不回填旧隐藏记录；回退不删除审计、不恢复状态或计数。选定本机验证已完成，证据见7O阶段记录。

## 单条回复隐藏审计查询（Phase 7P）

Controller：[AdminBlogCommentsController.java](../../src/main/java/io/github/frewily/campushub/controller/AdminBlogCommentsController.java)；服务：[BlogCommentsServiceImpl.java](../../src/main/java/io/github/frewily/campushub/service/impl/BlogCommentsServiceImpl.java)；响应：[BlogReplyHideAuditResponse.java](../../src/main/java/io/github/frewily/campushub/dto/response/BlogReplyHideAuditResponse.java)。范围和实测记录见[7P](../refactor/38-phase-7p-reply-audit-query.md)。

| Method | Path | Handler；认证与资源限制 | 请求与约束 | 成功响应 `data` / 缺失资源语义 |
|---|---|---|---|---|
| GET | `/admin/blog-comments/replies/{replyId}/hide-audit` | `queryReplyHideAudit`；需认证；`hasRole('ADMIN')`。匿名、停用、无有效角色401，有效USER/MERCHANT403，包括回复作者或原操作人 | 正Java Long `replyId`；无body。只读REPEATABLE READ事务；参数化普通SELECT V013，5秒语句超时，不加锁、不join当前业务行 | 200 `Result.ok(dto)`；九字段：String `id`、`replyId`、`blogId`、`rootCommentId`、`operatorUserId`；`reason`、`fromStatus`、`toStatus`、`createTime`。五个ID为十进制字符串；不含正文、资料或当前状态。无审计404；非法/溢出ID400；数据库异常无cause通用500 |

只读取当前事务快照可见的已提交存储记录；旧RR快照可能继续404，不读脏数据，不承诺即时看到之后提交的记录。审计独立于当前回复、根、动态、原操作人，移除这些行不会自动删除审计；缺审计的旧status=2不能推断为成功审核。V008根评论单条/分页仍不混入V013；新接口不是审计分页、导出、恢复或防篡改日志。不新增DDL，依赖已审查应用的V013；只读不会改变状态、计数或审计。学习见[单条回复审计](../learning/reply-hide-audit-query.md)。

## 按动态分页读取回复隐藏审计（Phase 7Q）

Controller：[AdminBlogCommentsController.java](../../src/main/java/io/github/frewily/campushub/controller/AdminBlogCommentsController.java)；请求复用通用游标字段：[BlogCommentHideAuditPageRequest.java](../../src/main/java/io/github/frewily/campushub/dto/request/BlogCommentHideAuditPageRequest.java)；响应：[BlogReplyHideAuditPage.java](../../src/main/java/io/github/frewily/campushub/dto/response/BlogReplyHideAuditPage.java)。阶段范围见[7Q记录](../refactor/39-phase-7q-reply-audit-pages.md)。

| Method | Path | Handler；认证与资源限制 | 请求与约束 | 成功响应 `data` / 缺失资源语义 |
|---|---|---|---|---|
| GET | `/admin/blog-comments/replies/hide-audits/of/blog/{blogId}` | `listReplyHideAudits`；`hasRole('ADMIN')`，按当前身份校验；匿名/停用/无有效角色401，USER/MERCHANT403，包括原操作人 | 正Long blogId；可选正Long beforeId为审计ID，size默认20、1..50，忽略未知query；参数化普通SELECT按blog、严格小于审计游标、id DESC，limit=size+1，5秒语句超时 | 200 `Result.ok(page)`：items复用7P九字段元数据（五个ID字符串）、hasNext、nextBeforeId。多取一行后裁剪，hasNext为true才返回末条已返回审计ID字符串作为游标，false时NON_NULL省略。无审计/空结果/当前动态缺失均空页成功，不查总量或join当前实体。非法参数400，数据库异常无cause通用500 |

每页只读RR快照，不加行锁、不写状态/计数/审计。V013回复审计不混入V008根分页；业务行或原操作人外部移除不影响保留元数据。自增ID分配不等于提交顺序，跨页不冻结，也不承诺全量无遗漏导出。V014增加候选(blog_id,id)索引，已有库须先备份、核结构并审查应用，重复按索引名跳过但不修同名错形状；没有性能提升或优化器采用证明。学习见[回复审计游标](../learning/reply-hide-audit-pagination.md)。

## 单条隐藏审计查询（Phase 7G 文档契约）

本节记录应用端单条读取契约。实现和本轮选定验证完成：26 项定向、360 项默认统计（4 设计跳过）、43 项显式 MySQL IT、23 组目标 Compose 通过；见[7G 阶段记录](../refactor/29-phase-7g-comment-audit-query.md)。Controller：[AdminBlogCommentsController.java](../../src/main/java/io/github/frewily/campushub/controller/AdminBlogCommentsController.java)；响应：[BlogCommentHideAuditResponse.java](../../src/main/java/io/github/frewily/campushub/dto/response/BlogCommentHideAuditResponse.java)；审计表仍为[V008](../../src/main/resources/db/migration/V008__add_blog_comment_hide_audit.sql)。

| Method | Path | Handler；认证与资源限制 | 请求与约束 | 成功响应 `data` / 缺失资源语义 |
|---|---|---|---|---|
| GET | `/admin/blog-comments/{commentId}/hide-audit` | [`queryHideAudit`](../../src/main/java/io/github/frewily/campushub/controller/AdminBlogCommentsController.java)；需认证；`hasRole('ADMIN')`。匿名、停用账号和无效/未知角色为 401；USER/MERCHANT 为 403，包括评论作者或原操作人 | Path `commentId` 必须是正 Java Long；无 body。使用普通参数绑定、参数化 SELECT；5 秒查询超时；只读 REPEATABLE READ 事务 | `Result.ok(dto)`；DTO 字段白名单：`String id`、`commentId`、`blogId`、`operatorUserId`，`String reason`，`Integer fromStatus`、`toStatus`，`LocalDateTime createTime`。按审计记录独立读取，不连接当前评论、动态或用户，不推断当前状态；没有对应审计行为 404。非法/溢出 ID 为 400，数据库内部错误为通用 500 |

查询只读取已提交且对本事务快照可见的审计行，不读取脏数据或其他事务未提交的记录；在查询前已建立的 REPEATABLE READ 事务可能继续看到旧快照。普通一致性 SELECT 不使用 `FOR UPDATE`。记录只是 V008 的状态转换元数据，不是防篡改证据；不回填历史隐藏记录。该单条接口不修改评论状态或动态计数，也不负责列表；按动态分页由下节 7H 提供。仍无导出、恢复、审核 UI 或完整管理后台，见[7G 学习笔记](../learning/comment-hide-audit-query.md)。

## 按动态分页读取隐藏审计（Phase 7H 文档契约）

本节定义已实现的应用端 ADMIN 按动态读取隐藏审计分页契约；本轮验证与边界见[7H 记录](../refactor/30-phase-7h-comment-audit-pages.md)。只读取已有 V008 审计，不加入评论列表、全局列表或导出。处理器为 `AdminBlogCommentsController.listHideAudits`；候选索引迁移为 `V009__add_blog_comment_hide_audit_page_index.sql`，已在隔离数据库验证 DDL，不据此宣称性能改善。

| Method | Path | Handler；认证与资源限制 | 请求与约束 | 成功响应 `data` / 缺失资源语义 |
|---|---|---|---|---|
| GET | `/admin/blog-comments/hide-audits/of/blog/{blogId}` | `listHideAudits`；`hasRole('ADMIN')`。匿名、停用账号、无效/未知角色为 401；已认证 USER/MERCHANT 为 403 | `blogId` 为正 Java Long；可选 `beforeId` 为正 Java Long，表示审计行 ID（不是评论 ID）；`size` 默认 20，范围 1..50；忽略未知 query 参数。只读 RR 普通 SELECT：按 blog_id 和可选 `id < beforeId` 筛选，`ORDER BY id DESC LIMIT size+1`，5 秒超时 | HTTP 200 `Result.ok(page)`：`items`、`hasNext`、`nextBeforeId`。`items` 复用八字段审计 DTO。多取一行确定 `hasNext` 后裁剪；仅 `hasNext=true` 时返回最后一条已返回审计 ID 的字符串 `nextBeforeId`，否则因 NON_NULL 配置省略 null。动态缺失、无审计或结果为空均为 `items:[]`、`hasNext:false`，不做 blog join |

每页在自己的 RR 只读事务读取，不提供跨页冻结快照。审计 ID 分配顺序不等于事务提交顺序；新记录通常排在首屏，但提交乱序时，后提交的较小 ID 可能落在已翻过的游标之后而被漏看。此接口不承诺跨页无遗漏、全量一致快照、性能 SLA 或延迟指标。保留的审计行不依赖当前评论、动态或操作人行；不查总量、不 COUNT、不改状态或计数，不提供全局列表、导出、恢复、UI、修复或对账。非法参数为 400，数据库访问异常映射为不带 cause 的通用 500。

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

## 独立管理端（不计入 45 条）

`SecurityConfig.managementSecurityFilterChain` 与 `application.yaml` 暴露两个 Actuator GET：`GET /actuator/health`、`GET /actuator/prometheus`。它们不属于业务 Controller 清单；管理监听地址默认 `127.0.0.1:8082`（端口可由配置替换），管理链对这两个 GET 公开并拒绝其他 Actuator 请求。Actuator 路由由 Spring Boot 管理端框架提供。
