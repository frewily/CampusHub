# Phase 6E2：限量活动有限批次实测

2026-10-10，原生 k6 对一个全新隔离环境完成六个正式批次及六个独立预热。正式共 **1320 次请求：330 个新受理、330 次原单重放、660 次正常 SOLD_OUT**；3960 项响应检查通过，非预期 HTTP/业务错误为 0。预热另有 120 次请求、30 个新订单，不混入正式统计。

这是不同合成账号竞争半数库存的有限批次，不是持续 QPS、订单 TPS、同步起跑、峰值容量或生产 SLO。受理成功只表示进入 Redis/Stream；本轮另核对异步落库后的完整身份与库存。

原始证据：[manifest](flash-sale-results/20261010T041031Z/manifest.json)、[12 份未经修改的 k6 聚合汇总](flash-sale-results/20261010T041031Z)。目录名是 UTC 发布时刻，不是业务活动 ID。方案与发布门槛见 [负载方案](flash-sale-plan.md)，实现修复及验证过程见 [阶段记录](../refactor/21-phase-6e2-flash-sale-results.md)。[6C 只读报告](README.md) 是另一组历史证据，不能互相替代。

## 客户端分类耗时

下表为同一账号规模的三轮 **逐轮 P95/P99 中位数 [最小–最大]**，单位 ms；不是合并请求分布的百分位数。每轮新受理/重放各只有 10 或 100 个样本，售罄有 20 或 200 个样本，尤其不能外推尾延迟。

| 合成账号 / VU | 响应类别 | 各轮 P95 (ms) | 各轮 P99 (ms) |
| --- | --- | --- | --- |
| 20 | 新受理 | 10.719 [8.472–18.978] | 10.772 [9.274–19.346] |
| 20 | 原单重放 | 8.337 [7.177–18.959] | 8.499 [7.204–19.219] |
| 20 | 正常售罄 | 12.381 [9.439–21.271] | 12.848 [9.785–21.586] |
| 200 | 新受理 | 175.491 [64.519–590.272] | 250.328 [66.246–600.282] |
| 200 | 原单重放 | 311.205 [262.065–504.449] | 320.337 [267.543–560.320] |
| 200 | 正常售罄 | 362.128 [216.588–1037.675] | 366.415 [258.366–1084.736] |

HTTP duration 是客户端发送、等待和接收耗时，不包含连接建立，也不包含逐单异步落库。不同轮次的范围较宽；没有对 CPU、连接池或数据库做受控瓶颈实验，不能仅据此归因。manifest 保留 `http_reqs.rate`，但它只是短批客户端请求速率，这里不把它报告为持续 QPS 或订单 TPS。

驱动在 k6 退出后观察全部订单已落库且 pending=0，20 账号的逐轮观察区间中位数为 0.143 s [0.139–0.162]，200 账号为 0.823 s [0.182–0.874]。**这不是逐单 admission→commit 延迟**：区间含负载退出之后的 Docker CLI、SQL/Redis 查询及至少 0.5 s 的轮询等待（第一次就满足条件时不发生等待）；负载进程退出/导出又影响观察起点。不能推出精确完成时刻或逐单延迟分布。

## 模型与环境

- 六正式批次按 `repeat → actors` 顺序执行：20 / 200 个不同 USER 合成账号，各三轮；`per-vu-iterations`，每账号一个 VU、一次 iteration，对同一新活动连续两次 POST。库存分别为 10 / 100。每批前另建 10 账号、5 库存的预热活动。
- 没有同步起跑屏障、think time、稳态到达率或订单轮询 HTTP；连接复用保留。每次 HTTP timeout 5 s、maxDuration 30 s、gracefulStop 5 s、外部 k6 timeout 50 s；后台观察预算 90 s。实际单调时钟区间逐轮保存在 manifest，不把 30 s 预算写成实际持续时间。
- 每批新活动，复用合成账号、JVM、连接池和 DB 热状态，不在批次间重启应用。活动由合成 ADMIN 经真实接口创建，ADMIN 不参与。DB/Redis fixture 只代替注册/短信供给身份；真实认证过滤器仍查账号状态、角色和会话，不是短信登录负载。
- 唯一 Compose 项目、随机 localhost 端口、全新卷；不读个人 `.env`、不连接共享库。负载 host 固定 `127.0.0.1`，初始化及 k6 请求不走下载代理，禁止重定向。请求头遵循当前原始 authorization 协议，不使用 Bearer。
- Apple M5 / Mac17,3，10 核、16 GiB；macOS 27.0.1 arm64。Docker Engine 29.6.1，VM 10 CPU / 8321515520 字节；Compose 5.3.0。MySQL 8.4.11、Redis 6.2.24、Prometheus 3.5.0。
- 宿主 Java 8 构建为 Corretto 1.8.0_492 / Maven 3.9.9；应用容器实际为 Temurin 1.8.0_504-b01。应用 2 CPU / 768 MiB，JVM `-Xms256m -Xmx512m`；MySQL 1 CPU / 768 MiB；Redis、Prometheus 各 0.5 CPU / 256 MiB。实际容器限制已核对。
- 官方原生 k6 1.3.0，`commit/5870e99ae8, go1.25.1, darwin/arm64`；archive 与 binary 固定 SHA-256，临时准备、不全局安装。原生 `GOMAXPROCS=1`、`GOMEMLIMIT=256MiB` 是 Go 软设置，不是 OS 硬配额；所有进程仍竞争同宿主资源。没有同时进行其他构建/验收，但驱动不能证明宿主完全无干扰。

应用运行时代码为 `f5d9ee8f7c9a5ef8ff7af24ae72676db0e0bc364`；Java/Lua/schema/正常部署未修改。执行驱动包含本阶段的解析/隐私修复，不能把该 revision 当作修复后驱动的 Git 标识；实际驱动、脚本、支持代码、overlay、JAR 和 k6 由 manifest 的哈希标识，修复随 6E2 提交交付。JAR 哈希不保证异时构建字节可重现。

## 正确性、发布和独立复核

每次响应验证状态、业务契约、重放结果。首受理者第二次必须返回相同字符串 orderId 与 `replayed=true`；售罄者第二次仍须 `success=false,errorCode=SOLD_OUT`。legacy 数字 data 只核对正整数及字符串 ID 的数值投影，不用 JS number 证明 64 位身份。其他 409、401/403/429/503、网络/JSON/ID 异常均使批次无效；零非预期错误不等于所有请求都新建订单。

12 批均验证 HTTP 私有见证 → Redis user→order 映射 → 参与集合 → 每个 ID 恰好一个 Stream 事件 → 同用户/活动的未支付 DB 订单。DB/Redis 库存均为 0，Stream 增量等于新订单数，没有重放重复追加；消费组 last-delivered-id 追平末条、pending=0，DLQ、尝试/失败索引与取消 outbox 均为 0。正式 330 个新订单之外还有预热 30 个，不能用全库 360 单冒充正式结果。

清理之后再发布；独立复核脚本重新对照私有原始文件、响应见证和 SQL/Redis 输出，确认公开 12 文件逐字节一致、哈希/计数/全部 HTTP 计时分项与三类 trend 合法、统计可重算、运行时标识一致。另查唯一项目的容器、网络、卷和应用镜像均为空；基础镜像保留。

公开内容只是 k6 聚合汇总和安全环境/账本摘要，不是逐请求样本、监控录像或逐单落库轨迹。会话、合成身份 ID、见证、fixture、原始日志和私有路径不进入 Git。隐私门禁只允许 k6 空 root_group 的固定 MD5 标识；同样的值在其他字段仍拒绝，不修改原始 JSON。定向检查不等于通用脱敏器。

## 复现与尚未验证

在可信的空闲 macOS ARM64 本机准备 Java 8、Python 3、Docker/Compose 和网络；不要连接共享环境。当前入口：

```bash
./mvnw clean package
python3 -m unittest discover -s scripts -p 'test_*baseline.py'
node scripts/test_flash_sale_script.mjs
export K6_BINARY="$(python3 scripts/prepare-native-k6.py)"
python3 scripts/run-flash-sale-baseline.py --inspect-only
python3 scripts/run-flash-sale-baseline.py --run
```

Node VM stub 与 inspect 不发真实 HTTP，不能代替最后一步。下载失败可在固定官方附件上续传，但归档/二进制哈希不得省略。只有六正式/六预热、身份对账、原始统计、隐私与清理全部通过才发布新目录；目录不可覆盖，失败结果不发布、不修改负值或删除慢轮。

本轮没有业务优化或前后对照，不包含持续容量、逐单落库延迟、冷启动、支付/取消竞争、故障注入、关键窗口 SIGKILL、多副本、HA、历史迁移、真实短信或生产 SLO。它补齐选定限量活动实际负载基线，不把原始全部产品/架构目标标为完成。
