package dev.githubstore.download

import dev.githubstore.domain.AssetPolicy
import dev.githubstore.domain.ReleaseAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.security.MessageDigest

class DownloadEngine(private val client: OkHttpClient, private val strictOrigin: Boolean = true) {
    @Volatile private var activeCall: Call? = null
    fun cancel() { activeCall?.cancel() }

    suspend fun download(repository: String, asset: ReleaseAsset, partial: File, destination: File,
                         onProgress: (Long) -> Unit, onVerifying: () -> Unit) {
        require(asset.enabled) { "这个安装包已停止分发" }
        require(AssetPolicy.validDigest(asset.sha256)) { "此文件缺少 SHA-256 校验信息，暂不能下载。请查看官方发布页。" }
        require(asset.size in 1..2_147_483_648L) { "安装包大小无效或超过首版支持范围" }
        val url = asset.url.toHttpUrl()
        if (strictOrigin) require(url.scheme == "https" && url.host == "github.com" &&
            url.encodedPath.startsWith("/$repository/releases/download/") && url.username.isEmpty() && url.password.isEmpty()) { "下载地址不是该应用的 GitHub 官方发布地址" }
        require(partial.parentFile!!.usableSpace > asset.size * 2 + 64L * 1024 * 1024) { "存储空间不足，请清理缓存或释放设备空间" }
        partial.delete()
        val downloadClient = if (!strictOrigin) client else client.newBuilder().addNetworkInterceptor { chain ->
            val requestUrl = chain.request().url
            check(requestUrl.scheme == "https" && requestUrl.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")) { "下载跳转到了未允许的来源" }
            chain.proceed(chain.request())
        }.build()
        val call = downloadClient.newCall(Request.Builder().url(url).header("User-Agent", "OrbitStore/0.1").build())
        activeCall = call
        try {
            call.execute().use { response ->
                check(response.isSuccessful) { when (response.code) {
                    404 -> "安装包已被上游移除，请刷新版本列表"
                    403, 429 -> "GitHub 暂时限制下载，请稍后重试"
                    else -> "下载服务器返回 ${response.code}"
                } }
                val body = response.body ?: error("下载内容为空")
                val length = body.contentLength()
                check(length < 0 || length == asset.size) { "上游文件大小已变化，请刷新后重试" }
                var total = 0L
                var lastReport = 0L
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= asset.size) { "下载内容超过预期大小" }
                        output.write(buffer, 0, count)
                        if (System.currentTimeMillis() - lastReport > 350) { onProgress(total); lastReport = System.currentTimeMillis() }
                    }
                } }
                check(total == asset.size) { "文件下载不完整，请重新下载" }
                onProgress(total)
                onVerifying()
                check(sha256(partial).equals(asset.sha256, ignoreCase = true)) { "文件校验失败，已阻止安装。请刷新版本后重试。" }
                currentCoroutineContext().ensureActive()
                check(partial.renameTo(destination)) { "无法保存安装包，请检查存储空间" }
            }
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally { activeCall = null }
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
