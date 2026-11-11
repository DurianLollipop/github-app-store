# GitHub 搜索与仓库管理（v0.3.0-dev）

搜索独立于首页，直接查询 GitHub 公开仓库。每页 20 项、防抖 800ms，显式搜索立即执行；支持取消旧查询、分页、空结果与限流退避。GitHub 搜索结果数量不等于可安装应用数量。

提交 `owner/repo` 或合法 GitHub 仓库地址后直接打开详情；不接受其他主机的仓库地址。使用 repository ID 核对已知仓库的改名 / 转移，不将旧同名路径自动当成同一个项目。

打开详情获取 Releases 与 README 展示资料，完整 README 通过应用内阅读器查看；真实语言文件可在菜单切换，未提供语言不生成翻译。图标在线加载，缺少 Logo 时保留作者头像或字母回退，不打包第三方图标。

默认最新稳定版，历史和多个 APK 变体默认收起；检查版本期间不启动缓存旧包下载。目录外仓库也可以下载独立 APK，系统安装前核对真实包信息并确认来源；协议不替代技术检查。缺少上游摘要不是人工审核前提，而是明确的检查状态。

收藏便于访问，显式订阅与已安装包的自动检查分开保存。列表返回顶部，保留查询和分页数据；README 子文档与图片预览返回保留上层阅读位置。

首次匿名使用无需账号或业务服务器。网络失败保留可用缓存，不把内置样本当真实搜索结果。接口与页面格式变化仍可能需要软件更新适配。

参考：[仓库搜索](https://docs.github.com/en/rest/search/search#search-repositories)、[仓库内容](https://docs.github.com/en/rest/repos/contents)、[Releases](https://docs.github.com/en/rest/releases/releases)。
