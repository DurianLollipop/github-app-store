package dev.githubstore.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Foreground reads have priority over discovery/polling; force refresh still respects quotas. */
class GitHubRequestPolicy(val background: Boolean = false, val revalidate: Boolean = false) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GitHubRequestPolicy>
}
data class ApiQuota(val remaining: Int, val resetAt: Long)
data class GitHubNetworkState(val authenticated: Boolean = false, val core: ApiQuota? = null, val search: ApiQuota? = null,
    val blockedUntil: Long = 0, val message: String? = null)
class GitHubRateLimitException(val retryAt: Long, message: String) : IOException(message)

/** One shared API queue and bounded public-response cache, independent of images/APK downloads. */
class GitHubTransport(private val client: OkHttpClient, private val token: () -> String? = { null },
    private val directory: File? = null, private val clock: () -> Long = System::currentTimeMillis,
    private val unauthorized: (String) -> Unit = {}) {
    private data class Cached(val body: String, val etag: String?, val modified: String?, val at: Long)
    private val mutex = Mutex()
    private val memory = object : LinkedHashMap<String, Cached>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 48
    }
    private var identity = ""
    private var backoff = 60_000L
    private val mutableState = MutableStateFlow(GitHubNetworkState())
    val state = mutableState.asStateFlow()
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun ttl(url: HttpUrl): Long = when {
        "/releases/assets/" in url.encodedPath -> 0L // Installation checks must never use stale identity.
        "/readme" in url.encodedPath || "/contents/" in url.encodedPath -> 24 * 3600_000L
        "/releases" in url.encodedPath -> 10 * 60_000L
        url.encodedPath.startsWith("/search/") -> 5 * 60_000L
        else -> 6 * 3600_000L
    }
    private fun remember(key: String, value: Cached) {
        memory[key] = value
        var bytes = memory.values.sumOf { it.body.length * 2L }
        val iterator = memory.entries.iterator()
        while (bytes > 6 * 1024 * 1024L && iterator.hasNext()) { bytes -= iterator.next().value.body.length * 2L; iterator.remove() }
    }
    private fun read(key: String): Cached? = memory[key] ?: runCatching {
        directory?.let { AtomicJson(File(it, "$key.json")).read() }?.let(::JSONObject)?.let {
            Cached(it.getString("body"), it.optString("etag").ifBlank { null }, it.optString("modified").ifBlank { null }, it.getLong("at"))
        }?.also { remember(key, it) }
    }.getOrNull()
    private fun save(key: String, value: Cached) {
        remember(key, value)
        directory?.let { dir ->
            dir.mkdirs()
            AtomicJson(File(dir, "$key.json")).write(JSONObject().put("body", value.body).put("etag", value.etag.orEmpty())
                .put("modified", value.modified.orEmpty()).put("at", value.at).toString())
            var bytes = 0L
            dir.listFiles()?.filter { it.name.endsWith(".json") && it.name != "quota.json" }?.sortedByDescending { it.lastModified() }
                ?.forEachIndexed { index, file -> bytes += file.length(); if (index >= 80 || bytes > 12 * 1024 * 1024L) file.delete() }
        }
    }
    private fun session(access: String?) {
        val next = digest(access ?: "anonymous")
        if (next == identity) return
        identity = next
        val old = runCatching { directory?.let { AtomicJson(File(it, "quota.json")).read() }?.let(::JSONObject) }.getOrNull()
        fun quota(name: String): ApiQuota? = old?.optJSONObject(name)?.let { ApiQuota(it.getInt("remaining"), it.getLong("resetAt")) }
        mutableState.value = if (old?.optString("identity") == next) GitHubNetworkState(access != null, quota("core"), quota("search"), old.optLong("blockedUntil"))
            else GitHubNetworkState(access != null)
    }
    private fun persistQuota() {
        directory?.let { dir ->
            dir.mkdirs(); val s = state.value
            fun json(q: ApiQuota?) = q?.let { JSONObject().put("remaining", it.remaining).put("resetAt", it.resetAt) }
            AtomicJson(File(dir, "quota.json")).write(JSONObject().put("identity", identity).put("core", json(s.core))
                .put("search", json(s.search)).put("blockedUntil", s.blockedUntil).toString())
        }
    }
    suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        val policy = currentCoroutineContext()[GitHubRequestPolicy]
        // No simultaneous GitHub API calls; cache is checked inside the lock to coalesce queued reads.
        mutex.withLock {
            val access = token()?.takeIf { it.isNotBlank() }; session(access)
            val key = digest("$identity:$url") // Isolate authorized responses from guest sessions.
            val cached = read(key)
            val now = clock(); val age = now - (cached?.at ?: 0)
            if (cached != null && ttl(url) > 0 && age >= 0 && age < ttl(url) && (policy?.revalidate != true || age < 1500)) return@withLock cached.body
            val search = url.encodedPath.startsWith("/search/")
            val s = state.value
            if (s.blockedUntil > now) throw GitHubRateLimitException(s.blockedUntil, "GitHub 限流冷却中，${time(s.blockedUntil)} 后可重试；缓存仍可浏览")
            var q = if (search) s.search else s.core
            if (q == null || q.resetAt <= now) q = ApiQuota(if (search) (if (access == null) 10 else 30) else (if (access == null) 60 else 5000), now + if (search) 60_000 else 3600_000)
            val reserve = if (search) 2 else 12
            if (q.remaining <= 0 || (policy?.background == true && q.remaining <= reserve)) {
                throw GitHubRateLimitException(q.resetAt, if (q.remaining > 0) "已暂停后台补全，为搜索和详情保留额度" else "GitHub 额度已用完，${time(q.resetAt)} 后恢复")
            }
            currentCoroutineContext()[RequestBudget]?.consume()
            q = q.copy(remaining = q.remaining - 1)
            mutableState.value = if (search) s.copy(search = q) else s.copy(core = q)
            persistQuota() // Conservatively debit even if the connection fails; persist across app restart.
            val builder = Request.Builder().url(url).header("Accept", "application/vnd.github+json").header("User-Agent", "OrbitStore-AndroidTV/0.3")
            access?.let { builder.header("Authorization", "Bearer $it") }
            cached?.etag?.let { builder.header("If-None-Match", it) }
                ?: cached?.modified?.let { builder.header("If-Modified-Since", it) }
            val call = client.newCall(builder.build())
            withGitHubResponse(call) { res ->
                val remaining = res.header("X-RateLimit-Remaining")?.toIntOrNull()
                val reset = res.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
                val resource = res.header("X-RateLimit-Resource") ?: if (search) "search" else "core"
                if (remaining != null && reset != null) {
                    val received = ApiQuota(remaining.coerceAtLeast(0), reset)
                    mutableState.value = if (resource == "search") state.value.copy(search = received) else state.value.copy(core = received)
                }
                if (res.code == 304 && cached != null) {
                    backoff = 60_000; persistQuota(); save(key, cached.copy(at = now)); return@withGitHubResponse cached.body
                }
                val body = res.body?.source()?.let { source ->
                    source.request(2 * 1024 * 1024L + 1)
                    check(source.buffer.size <= 2 * 1024 * 1024) { "GitHub 返回内容过大" }
                    source.readUtf8()
                }.orEmpty()
                val reason = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
                val limited = res.code == 429 || (res.code == 403 && (remaining == 0 || res.header("Retry-After") != null || reason.contains("rate limit", true) || reason.contains("abuse", true)))
                if (limited) {
                    val retry = res.header("Retry-After")?.toLongOrNull()?.times(1000)
                    val until = if (retry != null) now + retry.coerceAtLeast(1000) else if (remaining == 0 && reset != null && reset > now) reset else now + backoff
                    backoff = (backoff * 2).coerceAtMost(15 * 60_000)
                    // Primary buckets are independent; a secondary limit pauses the entire queue.
                    if (remaining != 0 || retry != null || reset == null || reset <= now) mutableState.value = state.value.copy(blockedUntil = until)
                    persistQuota()
                    throw GitHubRateLimitException(until, "GitHub 请求过于频繁，${time(until)} 后可重试")
                }
                persistQuota()
                if (!res.isSuccessful) {
                    if (res.code == 401 && access != null) unauthorized(access)
                    throw GitHubApiException(res.code, when (res.code) {
                        401 -> "GitHub 授权已失效，请在设置重新授权"
                        403 -> "GitHub 拒绝访问此资源，请检查仓库可见性或授权权限"
                        404 -> "GitHub 仓库或发布记录不存在"
                        422 -> "GitHub 无法识别这个搜索条件，请简化关键词"
                        else -> "GitHub 返回 ${res.code}，请稍后重试"
                    })
                }
                backoff = 60_000
                if (ttl(url) > 0) save(key, Cached(body, res.header("ETag"), res.header("Last-Modified"), now))
                body
            }
        }
    }
    private fun time(at: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))
    suspend fun clearCache() = withContext(Dispatchers.IO) { mutex.withLock {
        memory.clear(); directory?.listFiles()?.filter { it.name != "quota.json" }?.forEach { it.delete() }
    } }
}

internal suspend fun awaitGitHubResponse(call: Call): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
        }
    })
}

/** Keep cancellation attached after headers arrive, including a stalled response body. */
internal suspend fun <T> withGitHubResponse(call: Call, block: (Response) -> T): T = coroutineScope {
    val guard = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try { awaitGitHubResponse(call).use(block) }
    catch (error: IOException) { currentCoroutineContext().ensureActive(); throw error }
    finally { guard.cancel() }
}
