# GitHub 全站搜索

后续需求说明：本文描述 v0.2.2-dev 已实现行为。应用内 README 正文阅读与详情返回列表回顶尚待实现，以[工具化迭代需求 T11 / T12](tool-iteration-requirements.md)为准；下文保留滚动位置的行为不是新迭代的目标。

0.2.2-dev 的搜索独立于首页推荐目录，直接请求 GitHub `GET /search/repositories`。输入关键词后防抖 800ms，也可以使用搜索按钮或键盘搜索键立即提交；新查询取消旧请求，避免过期结果覆盖。

- 关键词原样交给 GitHub，支持 `topic:android-tv`、`repo:owner/name` 等仓库搜索语法，不把搜索范围限制到五款推荐应用。
- 每页 20 个仓库，通过“加载更多仓库”分页，遵守 GitHub 单次检索最多 1000 条结果的限制。
- 请求失败、限流、无结果分别展示真实状态，不用本地五款应用冒充在线结果。
- 搜索状态和滚动在页内返回时保留；内存中最多缓存 20 个查询页面、20 个已浏览的仓库详情。
- 选择任意仓库后获取其 GitHub Releases 和 README，解析介绍、Logo 和截图。README 不可用时保留仓库简介；没有识别到应用 Logo 时使用 GitHub 返回的仓库作者头像作为回退。
- 原有推荐应用匹配相同仓库时复用审核信息；搜索结果本身不会生成包名、发布证书或 TV 兼容认证。未审核仓库可查看版本并前往官方发布页获取文件，不能绕过现有安装信任检查。

当前仅查询仓库；不是代码文件内容搜索。GitHub 公共 API 会限流，未配置个人令牌；可以细化关键词或稍后重试。

接口依据：[仓库搜索](https://docs.github.com/en/rest/search/search#search-repositories)、[README](https://docs.github.com/en/rest/repos/contents#get-a-repository-readme)、[Releases](https://docs.github.com/en/rest/releases/releases#list-releases)。

## 详情版本选择

默认按发布时间选择最新稳定版；没有稳定版时选择最新测试版。页面只有当前版本摘要、说明与下载操作，历史版本默认收起在“版本”菜单里，使用遥控器或点击选择。显式选择旧版后，下载按钮、大小和说明同步更新；“最新稳定版”菜单项可恢复默认。

多个 APK 架构同样通过安装包菜单选择；唯一推荐架构自动选择。刷新最新版本期间不启动缓存旧版下载。安装校验和系统降级限制继续沿用现有流程。
