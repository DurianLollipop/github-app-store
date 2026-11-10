package dev.githubstore.domain

data class StoreApp(
    val id: String,
    val name: String,
    val repository: String,
    val packageName: String,
    val category: String,
    val tagline: String,
    val description: String,
    val license: String,
    val color: Long,
    val symbol: String,
    val iconUrl: String = "",
    val screenshots: List<String> = emptyList(),
    val mediaSource: String = "",
    val readmeUrl: String = "",
    val tvStatus: String = "尚未进行 TV 真机验证",
    val certificateSha256: Set<String> = emptySet(),
    val releases: List<AppRelease> = emptyList(),
    val checkedAt: Long = 0,
) {
    val sourceUrl get() = "https://github.com/$repository"
}

data class AppRelease(
    val id: Long,
    val tag: String,
    val name: String,
    val publishedAt: String,
    val notes: String,
    val prerelease: Boolean,
    val url: String,
    val assets: List<ReleaseAsset>,
)

data class ReleaseAsset(
    val id: Long,
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String?,
    val abi: String = inferAbi(name),
    val versionCode: Long? = null,
    val minSdk: Int? = null,
    val enabled: Boolean = true,
)

enum class TaskStatus(val label: String) {
    QUEUED("等待下载"), DOWNLOADING("正在下载"), VERIFYING("正在校验"),
    READY("等待安装"), WAITING_USER("等待系统确认"), INSTALLING("正在安装"),
    INSTALLED("安装完成"), FAILED("操作失败"), CANCELLED("已取消"), INTERRUPTED("下载已中断")
}

data class DownloadTask(
    val id: String,
    val appId: String,
    val appName: String,
    val packageName: String,
    val releaseTag: String,
    val asset: ReleaseAsset,
    val status: TaskStatus = TaskStatus.QUEUED,
    val downloaded: Long = 0,
    val error: String? = null,
    val sessionId: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val progress: Float get() = if (asset.size > 0) (downloaded.toFloat() / asset.size).coerceIn(0f, 1f) else 0f
    val active get() = status in setOf(TaskStatus.QUEUED, TaskStatus.DOWNLOADING, TaskStatus.VERIFYING)
}

data class InstalledApp(val packageName: String, val versionName: String, val versionCode: Long, val certificates: Set<String>)

fun inferAbi(name: String): String {
    val n = name.lowercase()
    return when {
        "arm64" in n || "aarch64" in n -> "arm64-v8a"
        "armeabi" in n || "armv7" in n -> "armeabi-v7a"
        "x86_64" in n || "x64" in n -> "x86_64"
        "x86" in n -> "x86"
        else -> "universal"
    }
}

object AssetPolicy {
    fun candidates(release: AppRelease, abis: List<String>, sdk: Int): List<ReleaseAsset> = release.assets
        .filter { it.enabled && it.name.endsWith(".apk", ignoreCase = true) }
        .filterNot { Regex("(^|[-_.])(debug|unsigned|test)([-_.]|$)", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
        .filter { it.minSdk == null || it.minSdk <= sdk }
        .filter { it.abi == "universal" || it.abi in abis }
        .sortedBy { if (it.abi == "universal") abis.size else abis.indexOf(it.abi) }

    // File names identify candidates only; equal-ranked variants require explicit choice.
    fun recommended(release: AppRelease, abis: List<String>, sdk: Int): ReleaseAsset? {
        val candidates = candidates(release, abis, sdk)
        val best = candidates.firstOrNull() ?: return null
        return best.takeIf { candidates.count { it.abi == best.abi } == 1 }
    }

    fun upgradeAvailable(installed: InstalledApp, asset: ReleaseAsset, certificates: Set<String>): Boolean =
        asset.versionCode?.let { it > installed.versionCode } == true &&
            certificates.isNotEmpty() && installed.certificates == certificates && asset.enabled

    fun validDigest(digest: String?): Boolean = digest != null && Regex("[a-fA-F0-9]{64}").matches(digest)
}

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "大小未知"
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
    else -> "%.0f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
}
