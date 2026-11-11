package dev.githubstore.data

import dev.githubstore.domain.StoreApp
import kotlinx.coroutines.CancellationException
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

class GitHubApiException(val status: Int, message: String) : IOException(message)

/** Search public GitHub repositories, independently of the curated home catalog. */
data class RepositoryPage(val apps: List<StoreApp>, val total: Int, val hasMore: Boolean, val incomplete: Boolean = false)

open class GitHubDiscovery(private val client: OkHttpClient, private val baseUrl: HttpUrl = "https://api.github.com/".toHttpUrl(),
    val transport: GitHubTransport = GitHubTransport(client)) {
    open suspend fun repository(name: String): StoreApp {
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(name)) { "请输入有效的 owner/repo" }
        return repository(JSONObject(fetch(baseUrl.newBuilder().addPathSegments("repos/$name").build())))
    }
    open suspend fun query(query: String, sort: String = "stars"): List<StoreApp> {
        val url = baseUrl.newBuilder().addPathSegments("search/repositories").addQueryParameter("q", query)
            .addQueryParameter("sort", sort).addQueryParameter("per_page", "20").build()
        return JSONObject(fetch(url)).getJSONArray("items").objects().map(::repository)
    }
    open suspend fun contents(app: StoreApp, path: String? = null): JSONObject {
        val endpoint = if (path == null) "repos/${app.repository}/readme" else "repos/${app.repository}/contents/$path"
        val url = baseUrl.newBuilder().addPathSegments(endpoint).addQueryParameter("ref", app.defaultBranch).build()
        return JSONObject(fetch(url))
    }
    open suspend fun directory(app: StoreApp, path: String = ""): List<String> = JSONArray(fetch(baseUrl.newBuilder()
        .addPathSegments("repos/${app.repository}/contents/$path").addQueryParameter("ref", app.defaultBranch).build()))
        .objects().filter { it.optString("type") == "file" }.map { it.getString("path") }
    open suspend fun asset(repository: String, id: Long): dev.githubstore.domain.ReleaseAsset {
        val j = JSONObject(fetch(baseUrl.newBuilder().addPathSegments("repos/$repository/releases/assets/$id").build()))
        return dev.githubstore.domain.ReleaseAsset(j.getLong("id"), j.getString("name"), j.getString("browser_download_url"), j.getLong("size"),
            j.optString("digest").removePrefix("sha256:").takeIf { dev.githubstore.domain.AssetPolicy.validDigest(it) }, updatedAt = j.optString("updated_at"))
    }
    open suspend fun starHistory(app: StoreApp): JSONArray = JSONArray(fetch(baseUrl.newBuilder()
        .addPathSegments("repos/${app.repository}/stargazers/history").addQueryParameter("per_page", "6").build()))
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
        val identified = if (app.githubId > 0) {
            // Resolve by immutable ID after rename/transfer, never trust a reused owner/name.
            val fresh = repository(JSONObject(fetch(baseUrl.newBuilder().addPathSegments("repositories/${app.githubId}").build())))
            app.copy(repository = fresh.repository, stars = fresh.stars, archived = fresh.archived, topics = fresh.topics, defaultBranch = fresh.defaultBranch)
        } else app
        val releases = try {
            listOf(CatalogRepository.parseGitHubRelease(JSONObject(fetch(baseUrl.newBuilder()
                .addPathSegments("repos/${identified.repository}/releases/latest").build()))))
        } catch (e: GitHubApiException) {
            if (e.status != 404) throw e
            JSONArray(fetch(baseUrl.newBuilder().addPathSegments("repos/${identified.repository}/releases")
                .addQueryParameter("per_page", "1").build())).objects().filterNot { it.optBoolean("draft") }.map(CatalogRepository::parseGitHubRelease)
        }
        var result = identified.copy(releases = (releases + identified.releases.filter { old -> releases.none { it.id == old.id || it.tag == old.tag } }), checkedAt = System.currentTimeMillis())
        try {
            val readme = contents(identified)
            if (readme.optString("encoding") == "base64") {
                val text = Base64.getMimeDecoder().decode(readme.getString("content")).toString(Charsets.UTF_8)
                val info = ReadmePresentation.extract(text, identified.repository, readme.optString("html_url"), readme.optString("path", "README.md"))
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

    suspend fun checkUpdates(record: UserRepository): StoreApp {
        val current = details(record.app)
        return if (record.prerelease || record.bindings.any { it.checks && it.prerelease }) current.copy(releases = (current.releases + history(current)).distinctBy { it.id }) else current
    }

    open suspend fun presentation(candidate: StoreApp): StoreApp {
        val app = if (candidate.githubId > 0) candidate else repository(candidate.repository).copy(id = candidate.id)
        val readme = contents(app)
        if (readme.optString("encoding") != "base64") return app
        val encoded = readme.optString("content")
        if (encoded.length > 1400_000) return app
        val text = Base64.getMimeDecoder().decode(encoded).toString(Charsets.UTF_8)
        val info = ReadmePresentation.extract(text, app.repository, readme.optString("html_url"), readme.optString("path", "README.md"))
        return app.copy(iconUrl = info.icon.ifBlank { app.iconUrl }, description = info.description.ifBlank { app.description },
            screenshots = info.screenshots.ifEmpty { app.screenshots }, readmeUrl = info.url)
    }

    protected suspend fun fetch(url: HttpUrl): String = transport.get(url)
    open suspend fun history(app: StoreApp): List<dev.githubstore.domain.AppRelease> = JSONArray(fetch(baseUrl.newBuilder()
        .addPathSegments("repos/${app.repository}/releases").addQueryParameter("per_page", "20").build())).objects()
        .filterNot { it.optBoolean("draft") }.map(CatalogRepository::parseGitHubRelease)

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
                tvStatus = "作者发布 · TV 兼容性未知，请查看项目说明",
                githubId = j.getLong("id"), stars = j.optLong("stargazers_count"),
                topics = j.optJSONArray("topics")?.strings().orEmpty(), archived = j.optBoolean("archived"),
                defaultBranch = j.optString("default_branch", "HEAD"))
        }
    }
}
