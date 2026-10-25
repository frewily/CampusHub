# 登出：撤销一个会话，不是封禁一个用户

## 这次拆了什么

原UserServiceImpl把验证码、登录和登出混在用户通用CRUD服务内。7AA只提取登出：LogoutController接收原authorization头，SessionLogoutService校验/trim，SessionTerminator表达当前会话终止，Redis适配器依次删除login:token:{token}和清理当前执行的旧UserHolder。服务不依赖Spring、Redis、Entity或ThreadLocal；适配器仍是共享遗留身份机制的桥接，不宣称完整identity隔离。

验证码/登录暂不动。独立接口不是为了增加抽象层数，而是避免登出用例暴露通用用户增删改查，并使删除/清理的顺序和失败边界可以单测。

## 三种不同的“重复”

内部调用删除一个已经不存在的key，Redis返回false；旧逻辑仍会清理当前身份并成功，不能把它改成404。若驱动返回null，同样保留原规则。

HTTP请求带已撤销token会先被认证过滤器拒绝，根本不会到登出服务，所以重复HTTP调用是401，不是“幂等200”。所有有效USER/MERCHANT/ADMIN均可登出，不复制签到等接口的角色限制。

同账号另一个token对应独立会话，不会被删除。query/body参数、外部userId或Bearer字符串不替代原authorization头的token来源；不新增全端登出、密码更改或封禁功能。

## 故障和并发边界

删除成功（含false/null）后才清UserHolder；Redis异常时适配器不提前清理或吞错。HTTP过滤器的finally仍清UserHolder和SecurityContext，这是原有请求生命周期保护，不代表Redis会话已经撤销。真实HTTP故障测试只禁止独立测试Redis的DEL，保留认证的HGETALL/EXPIRE，避免把“认证前Redis已经不可用”误当登出失败；finally恢复ACL后重试。

已经通过认证的并发请求仍可能继续，登出不是全局锁、会话代际检查或并发事务撤销。认证读取会话与登出删除也不是跨请求原子协议，不能由一个成功响应推导出所有并发业务立即停止。

## 验证与回退

应用单测覆盖空值/trim及端口异常；适配器单测覆盖删除前后身份、false/null和故障顺序；独立Redis IT验证只删一会话、不改别的key；真实安全过滤器契约与全新Compose验证HTTP状态/隔离和故障恢复。无新DDL或会话格式，clean构建完整jar替换，回退7Z无需数据回退；不承诺移除内部方法的Java二进制兼容。实际证据见[7AA记录](../refactor/49-phase-7aa-session-logout-module.md)，不等于生产或个人掌握。
