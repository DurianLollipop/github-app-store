# 构建与 Release 工作流验证

日期：2026-11-11。

| 检查 | 结果 |
| --- | --- |
| actionlint 1.7.12 | 两个工作流的 YAML、表达式、输入与权限语法通过；检查器从官方版本下载并核验官方 SHA-256 |
| Python | 22 项通过，含 6 项新增版本 / 打包检查，覆盖标签、版本递增、文件身份、路径和校验和 |
| Debug | 80 项 Android 单元测试通过，Lint 无 Error / Fatal，APK 与原生测试包构建通过 |
| Release | 80 项 Android 单元测试通过，Lint 无 Error / Fatal，R8 压缩、签名 APK 构建、mapping 生成通过 |
| 实际产物 | 从 AGP 输出元数据读取版本与包名，复制 APK、计算 SHA-256、生成机器元数据与校验和通过 |
| 签名校验 | 使用一次性测试密钥，apksigner 实际验证通过；临时密钥已删除，不用于发行 |
| 拦截分支 | 强制签名时缺少环境变量会失败；实际 APK 的证书与预期不匹配会失败 |
| 差异检查 | git diff --check 通过 |

Release 本地测试将版本从源码 `0.3.1-dev` 注入为 `0.3.1`，保持 versionCode 7；只是验证版本注入，没有创建 Git 标签或 GitHub Release。测试 APK 不作为正式安装包交付。

本轮没有访问或配置用户的正式密钥，没有触发真实云端构建 / 发布；云端依赖下载、仓库 token 权限、Secrets 与网络表现仍需提交工作流后验证。签名 APK 未重新执行实体 TV / 遥控器矩阵，既有原生 UI 验证不等同本次 R8 安装包已完成真机验收。

使用与配置见 [GitHub Actions 构建与发布](github-actions.md)。此前的 [v0.3.1 开发 APK 验证记录](verification.json) 保留原产物摘要，本轮测试没有将临时签名包冒充该开发产物或正式发行包。
