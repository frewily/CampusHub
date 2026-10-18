# 第一个业务切片：保留签到行为，拆开依赖

实际开发照着走：先列旧调用链及可观察契约，固定日期/Redis格式测试，再拆Controller、纯Java应用服务、存储端口和Redis适配器；最后检查真实Spring装配和旧位图。继续条件是旧HTTP/权限/响应和存量数据兼容，不是“类移动成功”。

可复制Prompt：只把现有签到迁入engagement，保持两个URL、角色准入、响应、月度Bitmap和默认时区不变；检查完整调用链，应用服务只依赖Clock与存储端口，提供固定日期和真实Redis测试。不要新增积分、补签或其他业务模块。

## 为什么拆这里

登录服务原来同时管验证码、会话与签到；签到实际不需要用户Mapper、短信或登录事务。7R把它放进目标engagement模块，应用服务接收已经认证的用户ID，不读取Web/ThreadLocal。Redis适配器负责具体命令；配置在装配边界提供 `Clock.systemDefaultZone()`，不向全局发布新Clock bean。

调用链：CheckInController（会话/响应/原权限）→CheckInService（取一次日期、统计末尾连续1）→CheckInStore→RedisCheckInStore（月键、SETBIT/BITFIELD）。接口是替换和测试边界，不是为了每个类都造接口。

## 不能顺手改变的旧规则

- 仍按JVM默认时区分月；并未标准化为北京时间或UTC。跨实例部署须统一时区，这不是本轮新保证。
- `sign:{userId}:yyyyMM`，月初为offset0，31日为offset30；读取unsigned(day)位，不读未来位。
- 今天未签就是0；跨月从0开始，不把上月最后一天连接到本月。
- 同日重复SETBIT是同一个位，GET不会写业务位图；没有新TTL或清理历史。
- ADMIN即使兼有USER/MERCHANT仍不能参与，身份不能由query userId替代。

## 权衡与追问

本轮只提取签到，仍复用dto.Result、utils.UserHolder和RedisConstants、既有认证组件。它们还没有搬入shared，其他业务也没有全部分包；先建一个可验证的业务边界，再逐个迁移，避免把全局包移动和功能变更混成一轮。

测试分三层：固定Clock/存储mock验证应用规则；私有Redis验证旧键/位操作和月边界；最终jar的Compose HTTP验证Spring实际装配和角色策略。mock与组件IT不等于部署验收，选定本机验收也不等于生产、持久化灾备或容量证明。

面试可追问：为何用端口而不直接调用Redis？默认时区跨实例如何治理？Bitmap适合算签到但为何不是积分账本？若以后支持跨月连续/补签，如何先定义兼容语义？这些后续设计未在本阶段实现。个人掌握仍需独立确认。
