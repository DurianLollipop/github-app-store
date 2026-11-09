# Orbit 开源商店

一款面向 Android TV 的 GitHub 开源应用商店。采用接近 tvOS 的简洁深色视觉：清晰的文字层级、宽松留白、圆角卡片、克制的焦点动效。

浏览开源应用，了解真实版本信息，从作者的官方发布下载 APK，并通过系统安装器完成安装与更新。首发 Android TV，后续计划扩展至 Android 手机和平板、Web，以及 Windows / macOS / Linux。

目前是 **v0.1 开发预览版**，可以编译成真实 Android APK。公开测试前仍需完成真机矩阵、更多应用审核及正式签名发布。

## 界面预览

以下均为 Android TV API 33 ARM64 模拟器的真实运行截图，分辨率为 1920 × 1080；点击图片可查看原图。

| 首页发现 | 应用详情 |
| :---: | :---: |
| [![首页：应用推荐与遥控器导航](docs/screenshots/home.png)](docs/screenshots/home.png) | [![详情：介绍、版本、大小与下载入口](docs/screenshots/details.png)](docs/screenshots/details.png) |
| **下载中心** | **系统安装确认** |
| [![下载中心：TV Bro 实际安装完成记录](docs/screenshots/downloads.png)](docs/screenshots/downloads.png) | [![系统安装器：用户确认 TV Bro 更新](docs/screenshots/system-install.png)](docs/screenshots/system-install.png) |

## 当前功能

- 首页发现、分类、中文搜索、详情介绍、真实截图预览。
- 从 GitHub 获取最近发布与历史版本，显示安装包大小、架构、版本说明和来源。
- 按设备架构与系统版本筛选 APK；存在多个同级变体时保留手动选择。
- 前台下载队列、进度、取消、失败重试、任务持久化与中断后重新下载。
- 安装前核对 SHA-256、包名、签名、内部版本、minSdk 和真实 APK 中的 ABI。
- 使用系统安装器确认安装，处理取消、冲突、空间不足和系统拒绝。
- 目录内已装应用识别、更新比较、忽略版本、打开及系统卸载入口。
- 商店自身更新入口，沿用相同的下载、校验和安装链路。
- 本地目录与发布记录缓存、限流退避、每 6 小时的后台检查。
- 可选的签名远程目录，验证发布者签名、递增版本及同版本内容一致性。
- 内容候选同步、APK 检查、显式审核和目录生成脚本，以及 GitHub Actions 构建检查。

内置 5 个开发样本；Jellyfin 和 TV Bro 已录入实际官方 APK 的包信息与签名，其余样本先用于浏览和内容开发。未经发布来源审核的应用会引导至官方发布页，不提供商店内安装。所有应用的真实电视遥控器兼容性仍需实机确认。

## 技术栈

| 领域 | 技术方案 |
| --- | --- |
| 客户端 | Kotlin、Jetpack Compose、Compose for TV |
| 状态与异步任务 | ViewModel、Coroutines / Flow、WorkManager |
| 网络与图片 | OkHttp、Coil |
| 本地持久化 | 原子 JSON 文件、SharedPreferences |
| 下载与安装 | 前台 dataSync 服务、PackageInstaller、PackageManager |
| 目录与校验 | Python、aapt、apksigner、SHA-256、RSA 签名快照 |
| 构建与测试 | Gradle、JUnit、Compose UI Test、UI Automator、GitHub Actions |

## 构建与运行

需要 JDK 17、Android SDK API 35、Build Tools 35.0.0。最低支持 Android 8.0 / API 26。用 Android Studio 打开仓库并同步 Gradle，或设置好 `ANDROID_HOME` 后运行：

```bash
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.githubstore/.MainActivity
```

首次安装其他应用时，需要在系统设置中允许 Orbit 安装来源，并在系统弹窗确认。开发 APK 使用本机调试证书；正式发行必须配置持久保存的发布证书。不同证书之间不能直接覆盖更新。

当前工作区安装的 SDK、Gradle 和模拟器位于 `.tooling/`，已排除在 Git 之外。`local.properties`、下载缓存和调试密钥也不提交。

目前尚未发布正式安装包。可以从源码构建；GitHub Actions 成功运行后，也可在对应工作流的 `orbit-android-tv` 构建产物中获取开发 APK。

## 验证

2026-11-10 已完成以下本地验证，详细结果与 APK 摘要见[验证记录](docs/verification.json)：

| 验证项 | 结果 |
| --- | --- |
| 单元测试 | 22 项通过，覆盖选包、版本比较、下载校验和目录签名 |
| 原生 UI 测试 | 4 项通过，覆盖遥控器导航、详情返回、搜索及空状态 |
| 真实下载安装 | TV Bro 官方 APK 下载、签名核对、系统确认与安装结果验证通过 |
| 构建与静态检查 | 开发 APK、原生测试包构建成功，Android Lint 无 Error / Fatal |

```bash
./scripts/check.sh
```

执行目录验证、单元测试、Android Lint、开发 APK 与原生 UI 测试包构建。

如果 SDK 中已安装 `emulator` 和 `system-images;android-33;android-tv;arm64-v8a`，可以启动本地无窗口 TV 模拟器：

```bash
./scripts/run-tv-emulator.sh
# 另一个终端，等待系统启动完成后运行：
./scripts/test-tv.sh
```

`test-tv.sh` 只连接 `emulator-5554`，清理该模拟器中的 Orbit 应用数据后验证详情与返回、遥控器导航、搜索及下载空状态。真实网络安装测试独立启用：

```bash
./scripts/test-live-install.sh
```

这个脚本会在本地模拟器中下载并安装 TV Bro 官方 APK，验证系统安装确认与实际安装结果；它不应连接个人日常使用的设备。默认 UI 测试不会运行此联网安装案例。

## 内容维护

```bash
# 只生成候选，不自动上架；令牌可通过 GITHUB_TOKEN 环境变量提供
python3 scripts/catalog.py sync --app jellyfin-tv --inspect --limit 3

# 发布证书指纹需要维护者独立核对，再明确批准某个候选版本
python3 scripts/catalog.py approve --app jellyfin-tv --tag v0.19.10 \
  --expected-cert d881796ed2a67ff6ef9f676828723c6b1fa18e09388962cba4abc4a594a69131

python3 scripts/catalog.py bundle
python3 scripts/catalog.py validate
```

- `catalog/sources.json`：介绍、分类、截图来源、许可证与包名。
- `catalog/reviewed.json`：核对过的版本、文件摘要和发布证书。
- `catalog/candidates/`：自动生成的候选，不提交。
- `app/src/main/assets/catalog.json`：可随 APK 分发的内置目录。
- `scripts/inspect-apk.py`：使用 SDK 的 `apksigner` 和 `aapt` 检查实际文件，不执行上游代码。

不在代码、目录或日志中放置 GitHub 令牌。公开 API 额度不足时等待恢复或在维护环境提供只读令牌；客户端不内置共享凭据。

## 可选的签名远程目录

默认没有配置远程目录地址，使用内置目录和 GitHub 发布记录。部署时用独立 RSA 私钥签名目录快照，客户端只包含公钥：

```bash
python3 scripts/sign-catalog.py --key /path/to/catalog-private.pem \
  --revision 1 --output /path/to/snapshot.json

./gradlew assembleDebug \
  -PorbitCatalogUrl=https://your-domain.example/snapshot.json \
  -PorbitCatalogPublicKey=BASE64_X509_DER_PUBLIC_KEY
```

`orbitCatalogPublicKey` 是 RSA 公钥的 X.509 DER 格式的 Base64 文本。私钥独立保管。快照签名覆盖原始 UTF-8 payload 字节，修订号只能递增；相同修订号不能替换内容。此仓库没有部署目录服务器，也没有生成正式发布私钥。

## 项目结构

```text
app/src/main/
├── java/dev/githubstore/   # UI、目录、选包规则、下载与安装
├── assets/catalog.json    # 随 APK 分发的目录
└── res/                   # 图标、主题与 TV 资源
app/src/test/              # 规则、下载与签名验证的单元测试
app/src/androidTest/       # 原生导航与显式启用的真实安装测试
catalog/                   # 应用来源与已审核发布记录
scripts/                   # 构建检查、目录维护及模拟器测试
docs/                      # 开发计划、实现记录与真实截图
.github/workflows/         # 持续构建配置
```

## 文档与当前边界

- [产品与开发计划](docs/development-plan.md)
- [当前实现与验证记录](docs/implementation-status.md)

首轮本地数据使用原子 JSON 文件持久化，业务接口与 UI 分离，后续可迁移到 Room。下载目前使用前台服务；进程终止后保留任务并提供重新下载，尚未实现 Range 续传。后台工作受 Android 与厂商策略限制，支持手动检查。

历史版本可以选择下载，但普通设备不保证直接覆盖降级。Split APK / XAPK / AAB、账户、全站应用索引及其他平台客户端均不属于当前开发版。

后续优先补齐实体电视测试、受控升级与降级场景、更多应用的截图和兼容性审核，再扩大目录并推进多平台支持。

## 反馈与贡献

欢迎通过 [GitHub Issues](https://github.com/DurianLollipop/github-app-store/issues) 提交问题、收录建议和功能需求。反馈 TV 兼容性问题时，请附设备型号、Android 版本、应用版本、操作步骤及系统提示；提交新应用时请提供官方仓库、许可证和发布页链接。

## 许可证与素材

本项目采用 Apache-2.0 许可证；第三方应用、截图、图标及品牌属于各自权利人。Jellyfin 截图来自其官方发布文章，按 CC-BY-ND-4.0 原图展示，来源记录在目录中。TV Bro 使用作者的自定义许可证，下载保持官方原始 APK，不进行修改或重新签名。

完整许可条款见 [LICENSE](LICENSE)。Orbit 为独立项目，与 GitHub、Apple 及所收录应用的作者无官方关联。
