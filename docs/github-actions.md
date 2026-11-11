# GitHub Actions 构建与发布

## 两套工作流

| 工作流 | 触发方式 | 输出 |
| --- | --- | --- |
| [Android TV - Build APK](../.github/workflows/android.yml) | 分支 push、Pull Request、手动 Run workflow | `orbit-android-tv-debug`，包含开发 APK、SHA256SUMS、build-metadata.json；测试报告单独上传，保留 14 天 |
| [Android TV - Release](../.github/workflows/release.yml) | 推送 `v*` 标签，或手动填写已有标签 | 签名 Release APK、SHA256SUMS、release-metadata.json，发布到 GitHub Releases；R8 mapping 另外保存在 Actions 产物，保留 30 天 |

工作流使用 JDK 17、Android 35 / Build Tools 35.0.0 和仓库固定的 Gradle Wrapper。开发构建执行 Python 测试、Android 单元测试、Lint，并编译原生测试 APK；Release 对 release 变体执行单元测试、Lint、R8 压缩与签名校验。云端工作流没有启动 TV 模拟器，不把测试 APK 编译等同于遥控器用例已经运行。

文件提交到 GitHub 后，打开仓库 **Actions → Android TV - Build APK → Run workflow**，成功后在运行页面底部 Artifacts 下载 `orbit-android-tv-debug`，解压安装 `.apk`。

开发 APK 使用运行器的调试证书，不保证不同运行之间能覆盖安装。持续升级请使用同一正式证书签名的 Release APK；已安装的本地开发版与正式版证书不同，不能直接覆盖。

## 配置正式签名（一次）

在仓库 **Settings → Secrets and variables → Actions → Secrets** 添加：

| Repository Secret | 内容 |
| --- | --- |
| `ORBIT_KEYSTORE_BASE64` | 正式 `.jks` / `.keystore` 文件的 Base64 内容 |
| `ORBIT_KEYSTORE_PASSWORD` | 密钥库密码 |
| `ORBIT_KEY_ALIAS` | 签名密钥别名，例如 `orbit` |
| `ORBIT_KEY_PASSWORD` | 该别名的密钥密码 |

如果已有发行证书，继续使用原证书。如果还没有，可以在本机交互式创建并妥善备份：

```bash
keytool -genkeypair -v -keystore orbit-release.jks -storetype JKS \
  -alias orbit -keyalg RSA -keysize 4096 -validity 10000
```

将文件编码后复制到 `ORBIT_KEYSTORE_BASE64`（Base64 仍是私钥材料，不要提交到仓库）：

```bash
python3 -c "import base64,pathlib; print(base64.b64encode(pathlib.Path('orbit-release.jks').read_bytes()).decode())"
```

工作流将密钥解码到运行器临时目录，仅在签名步骤暴露密码，结束时清理文件；不上传密钥。缺少任一 Secret 会停止发布，不回退到调试签名或未签名 APK。

可选在 **Variables** 添加：

| Repository Variable | 内容 |
| --- | --- |
| `ORBIT_GITHUB_CLIENT_ID` | GitHub Device flow 的公开 Client ID，自动内置到 APK；未配置仍可游客使用 |
| `ORBIT_RELEASE_CERT_SHA256` | 正式证书 SHA-256，用于校验首次及后续签名，可带冒号 |

后续发布还会与最近带 `release-metadata.json` 的版本比对证书，阻止意外更换签名。Action 自带的 `GITHUB_TOKEN` 只用于查询和发布，不写入 APK，无需配置个人 PAT。

## 发布版本

1. 修改 `app/build.gradle.kts` 顶部的 `orbitSourceVersionName` 和 `orbitSourceVersionCode`。每次发布，包括 RC 到正式版，`versionCode` 都必须增加。
2. 提交代码，确保发布提交包含工作流及打包脚本。
3. 创建并推送版本标签：

```bash
# 例：源码版本为 0.3.2-dev，versionCode 已增加
git tag v0.3.2
git push origin v0.3.2
```

首次发布当前源码可使用 `v0.3.1`；工作流会将 APK 中的 `0.3.1-dev` 替换为 `0.3.1`，不改写仓库文件。标签的主 / 次 / 修订版本必须与源码一致，拒绝 `-dev` 或任意文本标签。

标签 push 自动创建公开 Release，并由 GitHub 生成发布说明。`v0.3.2-rc.1`、`-alpha.1`、`-beta.1` 会标记为 Pre-release，不设为 Latest。普通分支 push 只构建，不发布。

也可以在 **Actions → Android TV - Release → Run workflow** 填入已有标签。手动触发默认创建草稿；取消勾选 `draft` 才直接发布。运行页面的分支用于选择工作流定义，实际构建内容始终检出填写的标签。

发布前读取最近 100 条 Releases：拒绝已有同名发布（包括草稿），并与最近已发布、带机器元数据的版本比较 `versionCode` 和证书。旧发布如果没有机器元数据，需要自行确认版本号及签名；新流程产生的发布会自动带元数据。重复运行不会覆盖已有资产；如失败留下草稿，先在 GitHub 核对并处理草稿，再重跑。

最终公开下载文件：

```text
orbit-tv-0.3.2.apk
SHA256SUMS
release-metadata.json
```

元数据记录真实 APK 版本、versionCode、提交 SHA、包大小、SHA-256、签名证书和构建链接。工作流在发布前再次核对远端标签的提交，避免构建期间移动标签造成产物与标签不符。

## 本地检查与边界

```bash
python3 -m unittest discover -s scripts/tests -v
./scripts/check.sh
actionlint .github/workflows/android.yml .github/workflows/release.yml
```

维护者可通过 `ORBIT_KEYSTORE_FILE`、`ORBIT_KEYSTORE_PASSWORD`、`ORBIT_KEY_ALIAS`、`ORBIT_KEY_PASSWORD` 环境变量在本地签名；设置 `ORBIT_REQUIRE_RELEASE_SIGNING=true` 强制完整校验。发布脚本只打包商店自己的单一 APK，拒绝其他包名、拆分输出和非空产物目录，避免混入旧 APK。

本轮本地验证记录见 [Actions 验证](github-actions-validation.md)。真实 GitHub 云端运行、仓库 Secrets 配置和正式发布需在工作流提交后执行；本次编写工作流没有创建标签或发布 Release。

实现参考：[GitHub Release CLI](https://cli.github.com/manual/gh_release_create)、[Android 应用签名](https://developer.android.com/studio/publish/app-signing)、[GitHub Actions 触发规则](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)。
