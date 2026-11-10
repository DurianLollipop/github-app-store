package dev.githubstore.data

import dev.githubstore.domain.StoreApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Search public GitHub repositories, independently of the curated home catalog. */
data class RepositoryPage(val apps: List<StoreApp>, val total: Int, val hasMore: Boolean, val incomplete: Boolean = false)

open class GitHubDiscovery(private val client: OkHttpClient, private val baseUrl: HttpUrl = "https://api.github.com/".toHttpUrl()) {
    open suspend fun search(query: String, page: Int = 1): RepositoryPage {
        require(query.isNotBlank() && query.length <= 256) { "请输入 1～256 个字符的仓库关键词" }
        require(page in 1..50)
        val url = baseUrl.newBuilder().addPathSegments("search/repositories")
            .addQueryParameter("q", query).addQueryParameter("per_page", "20").addQueryParameter("page", "$page").build()
        val result = JSONObject(fetch(url))
        val apps = result.getJSONArray("items").objects().map(::repository)
        val total = result.optInt("total_count")
        return RepositoryPage(apps, total, apps.size == 20 && page * 20 < minOf(total, 1000), result.optBoolean("incomplete_results"))
    }

    open suspend fun details(app: StoreApp): StoreApp {
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(app.repository))
        val releases = JSONArray(fetch(baseUrl.newBuilder().addPathSegments("repos/${app.repository}/releases")
            .addQueryParameter("per_page", "20").build())).objects().filterNot { it.optBoolean("draft") }
            .map(CatalogRepository::parseGitHubRelease)
        var result = app.copy(releases = releases, checkedAt = System.currentTimeMillis())
        try {
            val readme = JSONObject(fetch(baseUrl.newBuilder().addPathSegments("repos/${app.repository}/readme").build()))
            if (readme.optString("encoding") == "base64") {
                val text = Base64.getMimeDecoder().decode(readme.getString("content")).toString(Charsets.UTF_8)
                val info = ReadmePresentation.extract(text, app.repository, readme.optString("html_url"), readme.optString("path", "README.md"))
                result = result.copy(description = info.description.ifBlank { app.description },
                    iconUrl = info.icon.ifBlank { app.iconUrl },
                    screenshots = info.screenshots.ifEmpty { app.screenshots },
                    readmeUrl = info.url.ifBlank { app.readmeUrl },
                    mediaSource = if (info.screenshots.isNotEmpty()) info.url else app.mediaSource)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Releases remain usable when a README is absent or unavailable. */ }
        return result
    }

    private suspend fun fetch(url: HttpUrl): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
            .header("User-Agent", "OrbitStore-AndroidTV/0.2").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use { res ->
                        if (!res.isSuccessful) throw IOException(when (res.code) {
                            403, 429 -> "GitHub 请求过于频繁，请稍后重试"
                            422 -> "GitHub 无法识别这个搜索条件，请简化关键词"
                            404 -> "GitHub 仓库或发布记录不存在"
                            else -> "GitHub 返回 ${res.code}，请稍后重试"
                        })
                        val stream = res.body?.charStream() ?: throw IOException("GitHub 没有返回内容")
                        stream.use { reader ->
                            val text = StringBuilder(); val buffer = CharArray(8192)
                            while (true) {
                                val size = reader.read(buffer); if (size < 0) break
                                if (text.length + size > 2 * 1024 * 1024) throw IOException("GitHub 返回内容过大")
                                text.append(buffer, 0, size)
                            }
                            text.toString()
                        }
                    }
                    if (continuation.isActive) continuation.resume(body)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }

    companion object {
        fun repository(j: JSONObject): StoreApp {
            val fullName = j.getString("full_name")
            require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(fullName))
            val description = j.optString("description").takeUnless { it == "null" }.orEmpty()
            return StoreApp(id = "github-${j.getLong("id")}", name = j.getString("name"), repository = fullName,
                packageName = "", category = j.optString("language").takeUnless { it == "null" || it.isBlank() } ?: "GitHub 仓库",
                tagline = description.ifBlank { fullName }, description = description.ifBlank { "作者尚未提供仓库简介。" },
                license = j.optJSONObject("license")?.optString("spdx_id")?.takeUnless { it == "NOASSERTION" || it == "null" }.orEmpty().ifBlank { "未声明" },
                color = 0xFF4569A6, symbol = j.getString("name").take(1).uppercase(),
                iconUrl = ReadmePresentation.safeImage(j.optJSONObject("owner")?.optString("avatar_url").orEmpty()),
                tvStatus = "GitHub 搜索结果 · 兼容性请查看项目说明；发布安装包可在官方发布页获取")
        }
    }
}
