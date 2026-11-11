# v0.3.1-dev 请求优化与紧凑首页

日期：2026-11-11。延续 v0.3 工具化迭代；不增加业务后台或必需账户。

## 请求管理

- 首页、搜索、详情、README、安装前资产查询、主动 / 后台更新检查共用 GitHubTransport。普通 API 与 Search 分开记录响应中的 remaining / reset，初始匿名保守上限为 60 次 / 小时、仓库搜索 10 次 / 分钟；以服务端响应为准，不宣称本机知道同一出口 IP 的全部消耗。
- API 串行调度；后台补资料在普通额度剩余 12、搜索剩余 2 时暂停，预留给用户主动操作。首页每轮预算仅为额外约束，不能替代小时额度。排队时取消请求，避免已经离开页面仍耗费预取额度。
- 同 URL 的读取在缓存锁内合并，资料按 URL 与授权身份隔离。相同搜索刷新失败保留已有分页结果和查询，首次搜索失败不混入其他关键词的旧结果。README / Contents 24 小时、仓库资料 6 小时、版本列表 10 分钟、搜索 5 分钟；强制刷新仍遵守额度。磁盘响应缓存最多 80 份 / 12 MiB，内存最多 48 份 / 约 6 MiB 正文字符；清缓存不主动清空额度记录。
- ETag / Last-Modified 条件请求处理 304。只有正确认证后的 304，GitHub 才承诺不计入主要额度；匿名条件请求不视为免费。
- 区分访问权限 403 与真实限流；主额度按资源暂停，二级限流按 Retry-After 或指数退避暂停队列。界面提示预计恢复时间，不自动密集重试。
- 详情与阅读器共用相同 README API 缓存；版本默认请求 latest，历史菜单打开后才请求历史列表。已选择预发布检查的订阅 / 关联仍会获取对应历史，保留原有渠道行为。
- 跨平台 Trending 候选先显示，按可见网格项目补充资料与 README；离开页面取消。平台应用资格检查仍需要 Releases。已加载资料保留在本机用户库，图像沿用 Coil 缓存。
- Trending 网页单独冷却与快照缓存，不发送 API 授权令牌，也不承诺获得认证 API 的额度提升。APK 下载和外部图片请求不附带 GitHub 授权。

## 可选设备授权

设置 → GitHub 访问 → 配置 Client ID → 使用验证码授权。用户在手机浏览器打开 GitHub 官方 `github.com/login/device` 输入电视上的验证码；无需在电视输入密码。本版显示验证码和地址，未引入二维码或浏览器 SDK。

维护者需注册 GitHub OAuth App，启用 Device flow，然后配置公开 Client ID；没有 Client ID 时游客功能照常使用。正式构建建议预置公开标识：

```bash
./gradlew assembleDebug -PorbitGitHubClientId=你的公开ClientID
```

无需 Client Secret，不内置个人 Token，不申请 repo / 写入权限。遵守授权轮询间隔和 slow_down；取消弹窗停止轮询。获取授权后先核对 GitHub 用户身份，再通过 Android Keystore AES-GCM 加密保存令牌；本机备份关闭。退出删除本机凭据，GitHub 端彻底撤销通过授权管理页完成。令牌过期或 API 返回 401 时提示重新授权，不保存刷新令牌。

本轮用户尚未提供真实 Client ID，因此只完成可配置流程及受控协议验证，未把真实 GitHub 账号授权描述为已经验收。

## 首页布局

- 保留顶部玻璃导航与丝绸背景。
- 发现入口、筛选摘要和刷新合为一排；排序与今日 / 本周 / 本月进入筛选弹层，弹层保留完整来源和错误说明。
- 移除首页的大幅宣传标题和重复副标题，仅保留小标题、项目数量、更新时间及一行状态。
- 网格从屏幕前 40% 内开始，在标准 16:9 TV 密度下首屏可完整显示两行六列；滚动、详情回顶、焦点缩放和减少动态效果保留。
- 筛选关闭恢复原按钮焦点，上下键可在导航、工具栏和网格间切换。未以缩小到难以远距离阅读的文字来换取密度。

## 验证

构建与 Lint 通过；80 项 Android 单元测试、16 项 Python 测试通过。1080p 正常动画、720p 减少动画各 41 项原生测试通过；最终慢响应取消修复后另过 12 项重点原生复核。最终测试数字与 APK 摘要记录在 [verification.json](verification.json)。单元测试新增缓存 / 304、重复读取、主次限流、额度预留、凭据隔离、失效授权、资产检查绕过缓存、请求取消、懒加载历史版本，以及设备授权轮询 / 拒绝 / 过期 / 取消 / 地址与权限验证。

原生测试新增两行六列首屏、筛选弹层焦点恢复及 Keystore 加密 / 重启恢复 / 退出删除凭据。布局夹具截图仅使用测试数据，不是生产推荐名单；正式运行画面另行标注。实体电视性能与真实账号授权仍需后续验证。

最终 APK 的[真实热门页截图](screenshots/home-compact-live.png)取得 Trending 本周 14 个候选，首屏完整显示 12 个项目及新增 Star 指标。本次截图没有限流错误横幅，不能据此承诺后续不会限流；图像包含作者头像回退，不冒称全部识别到项目 Logo。[布局夹具截图](screenshots/home-compact-layout.png)则用于受控几何与焦点验证。

完整回归同时发现并修复了下载分段降级的竞态：首个 RangeIgnored 错误可能被取消其他连接产生的 IOException 抢先覆盖，现保留最初原因，让单连接降级正常执行；原回归增加连续运行覆盖。

官方依据：[额度规则](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)、[请求最佳实践](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api)、[设备授权](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow)。
