package dev.githubstore.download

import dev.githubstore.domain.AssetPolicy
import dev.githubstore.domain.ReleaseAsset
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Four bounded range connections per APK; task scheduling remains serial. */
class DownloadEngine(private val client: OkHttpClient, private val strictOrigin: Boolean = true,
                     private val parallelThreshold: Long = 4L * 1024 * 1024) {
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true; calls.forEach { it.cancel() } }
    private fun checkCancelled() { if (cancelled) throw CancellationException("下载已取消") }

    suspend fun download(repository: String, asset: ReleaseAsset, partial: File, destination: File,
                         onProgress: (Long) -> Unit, onVerifying: () -> Unit) = withContext(Dispatchers.IO) {
        require(asset.enabled) { "这个安装包已停止分发" }
        require(asset.sha256 == null || AssetPolicy.validDigest(asset.sha256)) { "上游文件摘要格式无效" }
        require(asset.size in 1..2_147_483_648L) { "安装包大小无效或超过首版支持范围" }
        val url = asset.url.toHttpUrl()
        if (strictOrigin) require(url.scheme == "https" && url.host == "github.com" &&
            url.encodedPath.startsWith("/$repository/releases/download/") && url.username.isEmpty() && url.password.isEmpty()) { "下载地址不是该应用的 GitHub 官方发布地址" }
        require(partial.parentFile!!.usableSpace > asset.size * 2 + 64L * 1024 * 1024) { "存储空间不足，请清理缓存或释放设备空间" }
        cancelled = false
        val downloadClient = if (!strictOrigin) client else client.newBuilder().addNetworkInterceptor { chain ->
            val requestUrl = chain.request().url
            check(requestUrl.scheme == "https" && requestUrl.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")) { "下载跳转到了未允许的来源" }
            chain.proceed(chain.request())
        }.build()
        val work = checkpointDir(partial)
        val metadata = File(work, "identity.properties")
        val identity = "${asset.id}\n${asset.url}\n${asset.size}\n${asset.sha256?.lowercase().orEmpty()}\n${asset.updatedAt}"
        val saved = Properties().apply { if (metadata.isFile) runCatching { metadata.inputStream().use { load(it) } } }
        if (saved.getProperty("identity") != identity) { discard(partial); saved.clear() }
        work.mkdirs()
        saved.setProperty("identity", identity)
        fun saveIdentity() {
            val temporary = File(work, "identity.tmp")
            FileOutputStream(temporary).use { saved.store(it, null); it.fd.sync() }
            check(temporary.renameTo(metadata)) { "无法保存续传记录" }
        }
        saveIdentity()
        val reportLock = Any()
        var lastReport = 0L
        fun report(count: Long, force: Boolean = false) = synchronized(reportLock) {
            checkCancelled()
            val now = System.nanoTime()
            if (force || now - lastReport >= 350_000_000) { onProgress(count); lastReport = now }
        }
        fun request(start: Long? = null, end: Long? = null, validator: String? = null) = Request.Builder()
            .url(url).header("User-Agent", "OrbitStore/0.2").header("Accept-Encoding", "identity")
            .apply {
                if (start != null) header("Range", "bytes=$start-${end ?: ""}")
                if (validator != null) header("If-Range", validator)
            }.build()
        suspend fun <T> response(request: Request, block: suspend (Response) -> T): T {
            checkCancelled(); currentCoroutineContext().ensureActive()
            val call = downloadClient.newCall(request)
            calls.add(call)
            try { checkCancelled(); return call.execute().use { block(it) } }
            finally { calls.remove(call) }
        }
        suspend fun copyBody(response: Response, file: File, append: Boolean, expected: Long, progress: (Int) -> Unit) {
            val body = response.body ?: throw InvalidPayload("下载内容为空")
            val length = body.contentLength()
            if (length >= 0 && length != expected) throw InvalidPayload("上游文件大小已变化，请刷新后重试")
            if (response.header("Content-Encoding")?.let { it != "identity" } == true)
                throw InvalidPayload("下载服务器返回了不兼容的压缩内容")
            var received = 0L
            body.byteStream().use { input -> FileOutputStream(file, append).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled(); currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    if (received > expected) throw InvalidPayload("下载内容超过预期大小")
                    output.write(buffer, 0, count)
                    progress(count)
                }
                output.fd.sync()
            } }
            if (received != expected) throw IOException("下载连接中断，已保存进度，可继续下载")
        }
        suspend fun sequential() {
            var offset = partial.length()
            if (asset.sha256 == null && saved.getProperty("validator").isNullOrBlank() && offset > 0) { partial.delete(); offset = 0 }
            if (offset > asset.size) { partial.delete(); offset = 0 }
            report(offset, true)
            if (offset == asset.size) return
            response(request(if (offset > 0) offset else null, validator = saved.getProperty("validator"))) { res ->
                checkStatus(res)
                if (res.code == 206) {
                    validateRange(res, offset, asset.size - 1, asset.size)
                    if (asset.sha256 == null && offset > 0 && res.header("ETag") != saved.getProperty("validator"))
                        throw InvalidPayload("续传文件标识发生变化，请重新下载")
                }
                else if (res.code == 200) { offset = 0; report(0, true) }
                else throw InvalidPayload("下载服务器未返回完整文件")
                saved.setProperty("mode", "single")
                val nextValidator = if (asset.sha256 == null) res.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') } else validator(res)
                if (nextValidator != null) saved.setProperty("validator", nextValidator) else saved.remove("validator")
                saveIdentity()
                copyBody(res, partial, offset > 0, asset.size - offset) { count -> offset += count; report(offset) }
            }
        }
        suspend fun parallel(): Boolean {
            var supported = false
            var currentValidator: String? = null
            response(request(0, 0)) { probe ->
                checkStatus(probe)
                if (probe.code == 206) {
                    validateRange(probe, 0, 0, asset.size)
                    // Consume and validate the probe, never retain it as a part.
                    val probeValid = probe.body?.byteStream()?.use { it.read() >= 0 && it.read() == -1 } ?: false
                    if (!probeValid) throw InvalidPayload("下载服务器返回了错误的分段内容")
                    currentValidator = if (asset.sha256 == null) probe.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') } else validator(probe)
                    supported = true
                }
            }
            if (!supported || (asset.sha256 == null && currentValidator == null)) return false
            if ((asset.sha256 == null && currentValidator == null) || saved.getProperty("mode") != "parallel" || saved.getProperty("validator") != currentValidator) {
                work.listFiles()?.filter { it.name.endsWith(".chunk") }?.forEach { it.delete() }
            }
            saved.setProperty("mode", "parallel")
            if (currentValidator != null) saved.setProperty("validator", currentValidator) else saved.remove("validator")
            saveIdentity()
            val ranges = (0 until 4).map { index ->
                val start = asset.size * index / 4
                val end = asset.size * (index + 1) / 4 - 1
                val file = File(work, "$index.chunk")
                if (file.length() > end - start + 1) file.delete()
                Triple(start, end, file)
            }
            val total = AtomicLong(ranges.sumOf { it.third.length() })
            report(total.get(), true)
            val firstFailure = AtomicReference<Throwable?>(null)
            try { coroutineScope {
                ranges.map { (start, end, file) -> async(Dispatchers.IO) {
                    val offset = start + file.length()
                    if (offset > end) return@async
                    try {
                        response(request(offset, end, currentValidator)) { res ->
                            checkStatus(res)
                            if (res.code == 200) throw RangeIgnored()
                            validateRange(res, offset, end, asset.size)
                            if (asset.sha256 == null && res.header("ETag") != currentValidator)
                                throw InvalidPayload("分段文件标识发生变化，请重新下载")
                            copyBody(res, file, file.length() > 0, end - offset + 1) { count -> report(total.addAndGet(count.toLong())) }
                        }
                    } catch (error: Throwable) {
                        // Preserve the initiating failure before cancelling siblings. Otherwise
                        // a cancelled socket can hide RangeIgnored and prevent safe fallback.
                        firstFailure.compareAndSet(null, error)
                        calls.forEach { it.cancel() }; throw error
                    }
                } }.awaitAll()
            } } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                throw firstFailure.get() ?: error
            }
            FileOutputStream(partial).use { output ->
                for ((_, _, file) in ranges) {
                    checkCancelled(); currentCoroutineContext().ensureActive()
                    file.inputStream().use { it.copyTo(output) }
                }
                output.fd.sync()
            }
            return true
        }
        try {
            // Retain an existing sequential partial even if parallelism is now available.
            if (asset.size >= parallelThreshold && partial.length() == 0L) {
                val transferred = try { parallel() } catch (_: RangeIgnored) {
                    work.listFiles()?.filter { it.name.endsWith(".chunk") }?.forEach { it.delete() }
                    false
                }
                if (!transferred) {
                    work.listFiles()?.filter { it.name.endsWith(".chunk") }?.forEach { it.delete() }
                    sequential()
                }
            } else sequential()
            checkCancelled(); currentCoroutineContext().ensureActive()
            report(asset.size, true)
            onVerifying()
            if (partial.length() != asset.size || (asset.sha256 != null && !sha256(partial).equals(asset.sha256, ignoreCase = true)))
                throw InvalidPayload("文件校验失败，已阻止安装。请刷新版本后重试。")
            checkCancelled(); currentCoroutineContext().ensureActive()
            check(partial.renameTo(destination)) { "无法保存安装包，请检查存储空间" }
            work.deleteRecursively()
        } catch (error: InvalidPayload) {
            discard(partial)
            throw error
        } finally { calls.forEach { it.cancel() }; calls.clear() }
    }

    private class InvalidPayload(message: String) : IOException(message)
    private class RangeIgnored : IOException()
    private fun checkStatus(response: Response) {
        check(response.isSuccessful) { when (response.code) {
            404 -> "安装包已被上游移除，请刷新版本列表"
            403, 429 -> "GitHub 暂时限制下载，请稍后重试"
            else -> "下载服务器返回 ${response.code}，已保留可续传进度"
        } }
    }
    private fun validator(response: Response): String? = response.header("ETag")?.takeUnless { it.startsWith("W/") }
        ?: response.header("Last-Modified")
    private fun validateRange(response: Response, start: Long, end: Long, size: Long) {
        if (response.code != 206 || response.header("Content-Range") != "bytes $start-$end/$size")
            throw InvalidPayload("下载服务器返回了错误的分段范围，已阻止合并")
    }
    companion object {
        fun checkpointDir(partial: File) = File(partial.parentFile, "${partial.name}.chunks")
        fun persistedBytes(partial: File): Long = if (partial.length() > 0) partial.length()
            else checkpointDir(partial).listFiles().orEmpty().filter { it.name.endsWith(".chunk") }.sumOf { it.length() }
        fun discard(partial: File) { partial.delete(); checkpointDir(partial).deleteRecursively() }
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
