# 限量活动负载：有限批次方案与验收门禁

当前状态：Phase 6E1 固定方案与离线门禁，Phase 6E2 已完成本文固定模型的真实有限批次、原始结果与清理复核，见 [实测报告](flash-sale.md) 与 [阶段记录](../refactor/21-phase-6e2-flash-sale-results.md)。本文仍是方案，不把预算或预期计数当容量结论，也不提供持续 RPS/TPS。

## 1. 回答什么问题

在一个合成活动中，用少量不同账号竞争不足库存，同时立即重放请求，检查受理、重放、售罄分类以及异步订单和库存是否收敛。它是有限、身份明确的并发批次，不是持续到达率、同步起跑、峰值容量、真实用户模型或生产 SLO。

当前接口规则是一人一活动一单。用一个账号循环请求会主要测重放；把售罄当故障会误报，把所有 HTTP 200 当新订单会虚增成功数。因此必须区分首次受理、同单重放与正常售罄。

## 2. 固定模型

| 项目 | 约定 |
| --- | --- |
| 正式批次 | 20 / 200 个不同合成账号，各三轮；`repeat → actors` 顺序，不挑最好一轮 |
| 预热 | 每个正式批次之前独立 10 账号、5 库存的新活动，完成落库/账本验证；不是 5 秒预热 |
| 执行器 | 原生 k6 `per-vu-iterations`，每账号绑定一个 VU，只执行一次 iteration |
| 请求 | 每账号同一活动连续两次 POST，先参与、再立即重放；无 think time，保留连接复用，无订单轮询混入 HTTP 测量 |
| 库存 | 正式批次账号数的一半；20 账号库存 10，200 账号库存 100 |
| 时间预算 | 批次 maxDuration 30 秒、gracefulStop 5 秒、每 HTTP 请求 timeout 5 秒；外部进程 timeout 50 秒 |
| 预期分类 | 每批 N 个账号：首次受理 N/2，重放 N/2，售罄 N；共 2N 请求、N iterations、6N 通过的检查 |
| 失败分类 | 非 SOLD_OUT 的 409、401/403/429/503、网络错误、无效 JSON、错误原 ID 等全部使批次无效 |
| 后台观察 | k6 退出后最多等待 90 秒，SQL/Redis 检查间隔至少 0.5 秒，含命令/查询开销 |

正式六批合计应为 1320 个 HTTP 请求，330 个新订单、330 个重放、660 个售罄。另有六批独立预热，共 120 请求、30 新订单；不把预热混入正式统计。批次初始化按序进行，不隔离 JVM/连接池/数据库热状态，也不在每批重启应用。

## 3. 身份、接口和测试数据

- 唯一新 Compose 项目、随机 localhost 发布端口、新卷、合成凭据；依赖为既有 MySQL 8.4 / Redis 6.2 / Prometheus overlay，不读取个人 `.env` 或连接共享库。
- 测试专用 DB fixture 创建 200 个 ACTIVE/USER 账号及一个 ADMIN，Redis fixture 建立随机会话；真实 HTTP 认证过滤器仍读取 DB 状态、角色和 Redis 会话。不是验证码、注册或短信负载。
- 活动通过合成 ADMIN 的真实 `/voucher/seckill` 创建；ADMIN 不参与，参与用 USER。测试前核对 Redis 初始化库存和合成会话身份。
- 路径为 `/voucher-order/seckill/{id}`，现有协议头是原始 `authorization` token，不加 Bearer。身份核对使用无精度损失的字符串 `orderId`；同时要求 legacy `data` 为正整数 number 且等于该字符串的 JS 数值投影，但后者不能证明精确的 64 位身份。
- 每批生成新的活动，但复用同一组合成账号。活动结束设为创建后 30 分钟，辅助记录保留期仍遵循业务规则；本阶段不改时间、资格、库存或重试策略。
- host 固定 `127.0.0.1`，只有 harness 发现的数值端口可传入；k6 禁重定向，初始化 HTTP 禁代理/重定向。不能把目标换成线上 URL。

## 4. 发布之前必须成立

1. HTTP 每响应三项检查，严格分类；首次成功者第二次必须返回同一个字符串 ID、`replayed=true`，售罄者第二次仍须 SOLD_OUT。即使首次传输异常，也尝试第二次，但不能把后续成功隐藏成有效批次。
2. 每个账号恰好一条私有见证，首次受理返回的订单 ID 与 Redis user→order 映射一致。见证只记录合成 userId/orderId/outcome，不记录 token、body 或活动 ID；日志不发布。
3. Redis 参与集合与受理映射相同；每个受理 ID 恰好一个 Stream 事件和同用户/活动的未支付 DB 订单。不能只比较 COUNT 而忽略错 ID、外来账号、重复或错误状态。
4. 对本批库存：DB 和 Redis 均为 0；当前 Stream 长度增量等于新订单数，没有因重放重复追加事件；消费组 last-delivered-id 追平最后记录且 pending=0。
5. 没有 DLQ、尝试/失败索引残留和取消 outbox。失败轮不发布，保留私有诊断，不自动修改负值或删样本。
6. k6 总量/分项和分组耗时，以及三类自定义耗时都为有限非负、分位数有序、平均值在 min/max 内；请求数、iterations、checks、三类计数准确。预热遵循同样检查。
7. 六个正式批次和六个预热均通过后，精确清理测试自己的容器、网络、卷、应用镜像并断言无残留。基础镜像保留。
8. 最后才发布 12 个未修改的 k6 聚合汇总及 manifest，记录原文件哈希、运行时代码/JAR、工具/脚本/支持代码/overlay 和环境。发布目录不可覆盖；token/验证码/私人路径和日志不得进入结果。

公开报告中的账本是合成观察的摘要，不提供密码、会话、user/order/activity ID 或私有见证原文。定向词/模式检查不是通用脱敏器；下一阶段仍需对实际导出内容独立复核。

## 5. 指标含义与局限

新受理、重放、售罄各自报告客户端 HTTP duration 的逐轮 P95/P99 中位数及 min/max，不能混成相同业务意义的延迟，也不能把几个分位数合并为请求总体分位数。duration 是发送/等待/接收，不包含连接建立或数据库异步消费完成。

脚本另外记录驱动的 monotonic 墙钟：k6 运行总时间，以及 k6 退出到首次观察“全部订单已落库、pending 清空”的时间。后者含 k6 退出/导出、Docker CLI、SQL/Redis 检查与轮询粒度，**不是每单 admission→commit 延迟**，也不证明后台早已完成或精确完成时刻。负载结束后轮询不会测出负载期间的逐单延迟分布；需要逐单延迟时，应另设计可核对的事件时间和观察方案。

k6 的 `http_req_failed` 把 200/409 都视为预期 HTTP 状态，但业务检查只承认严格 SOLD_OUT 的 409；其他 409 仍失败。报告中的零“非预期 HTTP 错误”不等于全是成功订单或没有正常业务拒绝。

有限批次的 `http_reqs.rate` 仅是该批客户端请求速率，不报告为持续 QPS、订单 TPS、峰值容量或 SLA。20/200 VU 不等于同步启动的 20/200 人，也不等于真实在线用户数。每轮只有 10/100 个新受理样本，尾分位数尤其不能外推。

资源复用 [6C overlay](../../compose.performance.yaml)：应用 2 CPU/768 MiB、JVM 256–512 MiB；MySQL 1 CPU/768 MiB；Redis/Prometheus 各 0.5 CPU/256 MiB。原生 k6 仅设 Go GOMAXPROCS=1、GOMEMLIMIT=256MiB，不是 OS 硬配额；测试与服务仍竞争同宿主资源。没有同环境优化前后对照、持续稳态、冷启动、取消竞争、故障注入、SIGKILL、多副本、HA、生产容量或真实短信结论。

## 6. 入口与阶段边界

Java 8 先构建；Python 3 运行离线门禁。Node.js 仅用于本地 VM stub 的脚本行为验证，不是应用运行依赖或原生负载工具。

```bash
./mvnw clean package
python3 -m unittest discover -s scripts -p 'test_*baseline.py'
node scripts/test_flash_sale_script.mjs
export K6_BINARY="$(python3 scripts/prepare-native-k6.py)"
python3 scripts/run-flash-sale-baseline.py --inspect-only
```

工具沿用官方 macOS ARM64 k6 1.3.0 和固定 archive/binary SHA-256，临时准备，不全局安装。`--inspect-only` 仅解析与检查选项，既不创建 Compose 资源，也不发 HTTP；VM 测试也是 HTTP stub，不是实际压测。`--run` 必须显式传入；6E2 实际通过的路线会建立全新环境，不连接自己的库：

```bash
python3 scripts/run-flash-sale-baseline.py --run
```

再次运行若遇到 native、CLI、账本或隐私不匹配，先定位根因并补回归，再完整重跑；无效批次不能算已验收。6E2 已补严格 CLI 文本整数边界、Redis 6.2 兼容的白名单只读 cjson 证据和 k6 空 root_group 固定标识的精确隐私白名单，不修改导出原文。6E1 的“当时尚未实际运行”保留为历史事实，见 [Phase 6E1](../refactor/20-phase-6e1-flash-sale-tooling.md)。总体仍按阶段验证、提交、独立审查后才继续。
