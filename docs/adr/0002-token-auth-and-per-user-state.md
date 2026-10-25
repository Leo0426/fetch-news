# token-auth-and-per-user-state

Status: accepted

fetch-news 需要通过 frp 暴露到公网，并让多人各自记录收藏和已读。我们采用**管理员签发的随机令牌**作为唯一凭据：每个账户一个登录令牌（256 位随机数，SQLite 只存 SHA-256 哈希，只显示一次）和一个只读的订阅密钥（放在 RSS 地址的 `?key=` 中）。浏览器登录后令牌写入 HttpOnly + SameSite=Lax Cookie；API 用 `Authorization: Bearer`；RSS 阅读器用 `?key=` 或 HTTP Basic。角色只有管理员和成员两种。

拆分登录令牌和订阅密钥，是因为 RSS 地址会出现在阅读器配置、同步服务和代理日志中，泄露概率远高于浏览器 Cookie；订阅密钥只能读取 feed，无法打开阅读器和后台。收藏、已读表增加 `user_id`，旧数据迁移给 id=1 的初始管理员，单用户安装升级后体验不变。

## Considered Options

用户名 + 密码 + 会话：
  优点: 用户熟悉
  缺点: 需要密码哈希、找回、暴力破解防护和会话表；对“自己发令牌给家人”的场景是多余复杂度

单一令牌（登录和 RSS 共用）：
  优点: 概念最少
  缺点: 任一 RSS 地址泄露即可登录网页、篡改收藏甚至进入后台

反向代理层认证（frp/nginx Basic Auth）：
  优点: 应用零改动
  缺点: 无法区分用户，收藏和已读不能按人隔离；RSS 阅读器兼容性差

## Consequences

- 认证默认开启；首次启动在日志中打印一次管理员令牌，`FETCH_NEWS_ADMIN_TOKEN` 用于固定或找回。本地/可信内网可用 `FETCH_NEWS_AUTH_ENABLED=false` 关闭。
- 令牌熵足够高，不做登录限流；如需要防扫描，应在 frps 或前置代理上做。
- 撤销是即时的：每个请求都按哈希查库，没有缓存的会话。
- 新增的个人数据必须按 `CurrentUser.id()` 存储；新增的静态资源必须登记到 `FrontendAssetResource.ASSETS`。
