# Phase 2D API 模型边界与验证记录

## 1 阶段范围

本阶段处理迁移计划指定的四类边界：商户门店写入、动态发布、优惠活动创建和用户资料读取。HTTP 路径、有效业务字段名、`Result` 包装、创建成功返回的 ID、数据库表名和 Redis key 保持不变。

新增五个请求模型、一个用户资料响应模型和显式映射器：

| 接口 | API 模型 | 内部业务实体 |
| --- | --- | --- |
| `POST /shop` | `ShopCreateRequest` | `Shop` |
| `PUT /shop` | `ShopUpdateRequest` | `Shop` |
| `POST /blog` | `BlogCreateRequest` | `Blog` |
| `POST /voucher` | `VoucherCreateRequest` | `Voucher` |
| `POST /voucher/seckill` | `FlashSaleCreateRequest` | `Voucher` + `SeckillVoucher` |
| `GET /user/info/{id}` | `UserInfoResponse` | `UserInfo` |

Controller 在请求校验和角色/资源授权通过后调用 `ApiModelMapper`，只将列出的业务字段转换成内部实体，再交给既有 Service。Service 中的商户资源授权仍然保留；动态作者继续由 Service 从 `UserHolder` 设置。

内部 Service 暂时沿用实体签名。本阶段没有把所有读接口、Mapper、Redis 缓存对象或服务层批量改成四套重复模型。门店、动态和优惠券的旧读取响应仍是下一步按用例演进的范围。

## 2 写入字段与服务端字段

### 门店创建与部分更新

- 创建必填：`name`、`typeId`、`images`、`address`、`x`、`y`。
- 创建可选：`merchantId`、`area`、`avgPrice`、`openHours`。商户账号仍必须提供有有效成员关系的 `merchantId`；管理员可以创建平台托管门店。
- 更新必填：正数 `id`；其余门店业务字段可选，继续沿用 MyBatis-Plus 忽略 `null` 字段的部分更新语义。这个接口不用于把数据库字段显式清空成 `NULL`。
- 更新模型不含 `merchantId`，服务层也继续保留已有归属，不允许借普通门店更新转移归属。
- 创建时 `sold`、`comments`、`score` 由服务端初始化为 `0`；更新时不接受这些统计值。
- `id`（创建时）、`distance`、`createTime`、`updateTime` 均不是可写字段。
- 字符串长度按旧表限制校验；ID 为正数，均价非负。
- 旧 `tb_shop.x/y` 是 `UNSIGNED DOUBLE`，因此写入经度限制为 `[0, 180]`，纬度限制为 `[0, 90]`。未来支持西经或南纬时需要先迁移列类型；本阶段不修改旧 schema。

### 动态发布

只接受 `shopId`、`title`、`images`、`content`，均不能为空，并检查字段长度与正数门店 ID。客户端不能指定动态主键、作者、点赞数、评论数、展示用户名或时间戳。映射后 `liked/comments` 初始化为 `0`，作者由已认证身份写入。

### 普通券与限量活动

- 公共字段：`shopId`、`title`、`subTitle`、`rules`、`payValue`、`actualValue`。
- 必填：正数门店 ID、非空标题、非负支付金额、正数抵扣金额。金额单位仍为分。
- 普通券创建固定 `type=0`、初始 `status=1`，不接受库存或活动时间。
- 限量活动继承公共字段，额外要求正数 `stock`、`beginTime`、`endTime`，结束时间必须晚于开始时间；固定 `type=1`、初始 `status=1`。
- 主键、类型、状态和时间戳不能由客户端覆盖。

这只是创建请求的基础校验。运行时活动状态、起止时间、用户资格和 Lua 受理规则仍属于 Phase 3A；没有把请求校验视为完整活动规则。

## 3 响应与兼容性

`UserInfoResponse` 显式保留旧资料响应中的 `userId`、`city`、`introduce`、`fans`、`followee`、`gender`、`birthday`、`credits`、`level`；不包含数据库创建/更新时间。Controller 不再通过将原实体的时间戳置空来隐藏字段。资料不存在时继续返回空的成功响应。

请求模型使用 `@JsonIgnoreProperties(ignoreUnknown = true)`：为兼容旧客户端携带冗余实体字段的行为，额外字段被忽略，不能进入写入映射。例如门店请求携带 `score=50`，动态请求携带 `userId=99`，普通券请求携带 `type=1`，都不能覆盖服务端字段。

写入接口现在会对缺失必填字段、空白字符串、错误 ID/金额、非法坐标、缺失库存或时间、反向或相同活动时间返回结构化 HTTP 400 和 `VALIDATION_FAILED`。合法请求的路径、字段名及 ID 响应保持不变，但旧客户端原本发送的不完整或不合法请求需要补齐字段。

## 4 验证结果

使用 JDK `1.8.0_492`，补齐依赖后执行最终离线全量测试：

```bash
JAVA_HOME=/path/to/jdk8 ./mvnw -B -o -Dmaven.repo.local=/private/tmp/campushub-phase2d-m2 clean test
```

实际结果：67 个测试，0 失败，0 错误，1 个依赖外部服务的手工集成测试按设计跳过，`BUILD SUCCESS`。

- `ApiModelHttpContractTest`：22 个测试，验证真实 JSON 绑定、Bean Validation、结构化 400 和传入服务的实体字段。覆盖五个写接口的字段伪造、门店部分更新、服务端 ID 响应、用户资料实体不被修改，以及 15 个非法请求在调用业务服务前被拒绝。
- `SecurityHttpContractTest`：6 个测试，使用真实 Spring Security 过滤链与方法级授权，验证匿名 401、管理员活动 403、商户创建归属、普通用户商户写入 403、优惠券所属门店授权及更新时按目标 `id` 校验归属。
- 既有服务、Redis Token、资源授权、Feed、关注、缓存、订单及静态迁移测试全部通过。
- 首次在线测试因四个依赖下载超时未进入编译；重试补齐缓存后在线测试通过，最终离线干净测试也通过。没有因此改动依赖版本或业务逻辑。

上述 HTTP 验证使用 MockMvc，业务服务或数据库/Redis 访问由 mock 提供。本阶段没有执行真实 MySQL 写入端到端、V002 实库迁移、完整应用启动或性能测试。Phase 2C 的实库补验限制继续保留；尚未进行实际压测。

## 5 设计与后续边界

设计取舍和面试追问见 [API 模型边界](../learning/api-model-boundaries.md)。Phase 2D 已完成上述最小边界及自动验证。后续阶段仍需按用例推进其他读取响应、活动受理规则和真实数据库集成验证。
