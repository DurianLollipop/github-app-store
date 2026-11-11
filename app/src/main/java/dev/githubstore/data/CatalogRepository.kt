package dev.githubstore.data

import android.content.Context
import dev.githubstore.BuildConfig
import dev.githubstore.domain.*
import dev.githubstore.install.PackageInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

class CatalogRepository(context: Context, private val client: OkHttpClient) {
    private val cacheDir = File(context.filesDir, "releases").apply { mkdirs() }
    private val snapshotDisk = AtomicJson(File(context.filesDir, "catalog-snapshot.json"))
    private var snapshot: VerifiedCatalog? = if (BuildConfig.CATALOG_PUBLIC_KEY.isBlank()) null else snapshotDisk.read()?.let {
        runCatching { CatalogSignature.verify(it, BuildConfig.CATALOG_PUBLIC_KEY) }.getOrNull()
    }
    private var curated = snapshot?.apps ?: JSONObject(context.assets.open("catalog.json").bufferedReader().use { it.readText() })
        .getJSONArray("apps").objects().map(JsonCodec::readApp)
    private val selfApp = StoreApp(
            id = "orbit-store", name = "Orbit 开源商店", repository = "DurianLollipop/github-app-store",
            packageName = context.packageName, category = "应用商店", tagline = "开源的精彩，来到大屏",
            description = "Orbit 的 Android TV 客户端。商店自身的更新同样从项目官方 GitHub Releases 下载，并要求与当前已安装版本保持相同发布签名。开发版与正式版签名可能不同，无法直接覆盖。",
            license = "Apache-2.0", color = 0xFF4569A6, symbol = "O", tvStatus = "Orbit 商店自身更新",
            certificateSha256 = PackageInspector(context).installed(context.packageName)?.certificates.orEmpty(),
        )
    private val seed get() = curated + selfApp
    private val _apps = MutableStateFlow(seed.map { app ->
        val cached = AtomicJson(File(cacheDir, "${app.id}.json")).read()?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (cached == null) app else runCatching {
            app.copy(releases = mergeReviewed(app, cached.getJSONArray("releases").objects().map(JsonCodec::readRelease)),
                checkedAt = cached.getLong("checkedAt"))
        }.getOrDefault(app)
    })
    val apps = _apps.asStateFlow()
    private val mutex = Mutex()
    private var blockedUntil = 0L

    fun find(id: String) = _apps.value.firstOrNull { it.id == id }

    suspend fun acceptDetails(details: StoreApp): StoreApp = mutex.withLock {
        val current = find(details.id) ?: return@withLock details
        val updated = current.copy(description = details.description, iconUrl = details.iconUrl,
            screenshots = details.screenshots, readmeUrl = details.readmeUrl, mediaSource = details.mediaSource,
            releases = mergeReviewed(current, details.releases), checkedAt = details.checkedAt)
        withContext(Dispatchers.IO) {
            AtomicJson(File(cacheDir, "${current.id}.json")).write(JSONObject().put("checkedAt", updated.checkedAt)
                .put("releases", JSONArray(updated.releases.map(JsonCodec::release))).toString())
        }
        _apps.value = _apps.value.map { if (it.id == current.id) updated else it }
        updated
    }

    suspend fun refreshSnapshot(): Boolean = mutex.withLock { withContext(Dispatchers.IO) {
        if (BuildConfig.CATALOG_URL.isBlank() || BuildConfig.CATALOG_PUBLIC_KEY.isBlank()) return@withContext false
        val request = Request.Builder().url(BuildConfig.CATALOG_URL).build()
        check(request.url.scheme == "https") { "目录地址必须使用 HTTPS" }
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful && response.request.url.scheme == "https") { "目录暂时不可用，已保留缓存" }
            check((response.body?.contentLength() ?: -1) <= 4 * 1024 * 1024) { "目录快照过大" }
            val envelope = response.body?.charStream()?.use { it.readTextLimited(4 * 1024 * 1024) } ?: error("目录内容为空")
            val verified = CatalogSignature.verify(envelope, BuildConfig.CATALOG_PUBLIC_KEY, snapshot?.revision ?: 0, snapshot?.fingerprint)
            snapshotDisk.write(envelope)
            snapshot = verified
            curated = verified.apps
            _apps.value = seed.map { app ->
                val current = find(app.id)
                if (current == null || app.id == selfApp.id) app
                else app.copy(releases = mergeReviewed(app, app.releases + current.releases.filterNot { old -> app.releases.any { it.tag == old.tag } }), checkedAt = maxOf(app.checkedAt, current.checkedAt))
            }
            true
        }
    } }

    suspend fun refresh(id: String, force: Boolean = false): StoreApp = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = find(id) ?: error("应用已不在目录中")
            if (!force && app.releases.isNotEmpty() && System.currentTimeMillis() - app.checkedAt < TimeUnit.MINUTES.toMillis(30)) return@withContext app
            if (System.currentTimeMillis() < blockedUntil) error("GitHub 请求额度已用完，请稍后重试。缓存仍可浏览。")
            val file = AtomicJson(File(cacheDir, "${app.id}.json"))
            val cached = file.read()?.let { runCatching { JSONObject(it) }.getOrNull() }
            val request = Request.Builder().url("https://api.github.com/repos/${app.repository}/releases?per_page=20")
                .header("Accept", "application/vnd.github+json").header("User-Agent", "OrbitStore-AndroidTV/0.1")
                .apply { cached?.optString("etag")?.takeIf(String::isNotEmpty)?.let { header("If-None-Match", it) } }.build()
            client.newCall(request).execute().use { response ->
                if (response.code == 403 || response.code == 429) {
                    val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
                    val retry = response.header("Retry-After")?.toLongOrNull()?.let { System.currentTimeMillis() + it * 1000 }
                    blockedUntil = maxOf(System.currentTimeMillis() + 60_000, reset ?: retry ?: System.currentTimeMillis() + 900_000)
                    error("GitHub 暂时限制请求，请稍后重试。已缓存的版本仍可查看。")
                }
                val releases = if (response.code == 304 && cached != null) {
                    cached.getJSONArray("releases").objects().map(JsonCodec::readRelease)
                } else {
                    check(response.isSuccessful) { if (response.code == 404) "仓库或发布记录不存在" else "GitHub 返回 ${response.code}，请稍后重试" }
                    val body = response.body?.string() ?: error("GitHub 没有返回数据")
                    JSONArray(body).objects().filterNot { it.optBoolean("draft") }.map(::parseGitHubRelease)
                }
                val merged = mergeReviewed(app, releases)
                val checkedAt = System.currentTimeMillis()
                val updated = app.copy(releases = merged, checkedAt = checkedAt)
                file.write(JSONObject().put("checkedAt", checkedAt).put("etag", response.header("ETag") ?: cached?.optString("etag"))
                    .put("releases", JSONArray(merged.map(JsonCodec::release))).toString())
                _apps.value = _apps.value.map { if (it.id == id) updated else it }
                updated
            }
        }
    }

    private fun mergeReviewed(app: StoreApp, releases: List<AppRelease>): List<AppRelease> {
        val approved = seed.first { it.id == app.id }.releases.flatMap { it.assets }
        return releases.map { release -> release.copy(assets = release.assets.map { asset ->
            val known = approved.firstOrNull { it.url == asset.url && it.sha256 == asset.sha256 && it.size == asset.size }
            if (known != null) asset.copy(versionCode = known.versionCode, minSdk = known.minSdk, abi = known.abi, enabled = known.enabled) else asset
        }) }.sortedByDescending { runCatching { Instant.parse(it.publishedAt).epochSecond }.getOrDefault(0) }
    }

    companion object {
        fun parseGitHubRelease(j: JSONObject) = AppRelease(
            id = j.getLong("id"), tag = j.getString("tag_name"), name = j.optString("name", j.getString("tag_name")),
            publishedAt = j.optString("published_at"), notes = j.optString("body").take(24000),
            prerelease = j.optBoolean("prerelease"), url = j.getString("html_url"),
            assets = j.optJSONArray("assets")?.objects().orEmpty().filter { it.getString("name").endsWith(".apk", true) }.map { asset ->
                ReleaseAsset(asset.getLong("id"), asset.getString("name"), asset.getString("browser_download_url"), asset.getLong("size"),
                    asset.optString("digest").removePrefix("sha256:").takeIf { AssetPolicy.validDigest(it) }, updatedAt = asset.optString("updated_at"))
            },
        )
    }
}

private fun java.io.Reader.readTextLimited(limit: Int): String {
    val result = StringBuilder()
    val buffer = CharArray(8192)
    while (true) { val size = read(buffer); if (size < 0) break; check(result.length + size <= limit) { "目录快照过大" }; result.append(buffer, 0, size) }
    return result.toString()
}
