package dev.githubstore.domain

import dev.githubstore.data.GitHubDiscovery
import dev.githubstore.data.ReadmePresentation
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class GitHubDiscoveryTest {
    private fun repo() = JSONObject().put("id", 42).put("name", "OutsideCatalog").put("full_name", "author/OutsideCatalog")
        .put("description", "not one of the five recommended apps").put("language", "Kotlin")
        .put("owner", JSONObject().put("avatar_url", "https://avatars.githubusercontent.com/u/42"))
        .put("license", JSONObject().put("spdx_id", "MIT"))
    @Test fun `search sends raw github query and page instead of filtering local applications`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(JSONObject().put("total_count", 101).put("items", JSONArray(listOf(repo()))).toString()))
            server.start()
            val result = GitHubDiscovery(OkHttpClient(), server.url("/")).search("电视 topic:android-tv", 2)
            val request = server.takeRequest().requestUrl!!
            assertEquals("/search/repositories", request.encodedPath)
            assertEquals("电视 topic:android-tv", request.queryParameter("q"))
            assertEquals("2", request.queryParameter("page")); assertEquals("20", request.queryParameter("per_page"))
            assertEquals("author/OutsideCatalog", result.apps.single().repository)
            assertEquals("", result.apps.single().packageName)
            assertTrue(result.apps.single().certificateSha256.isEmpty())
        }
    }
    @Test fun `pagination follows remote count with github search ceiling`() = runBlocking {
        MockWebServer().use { server ->
            val items = JSONArray((1..20).map { repo().put("id", it) })
            repeat(2) { server.enqueue(MockResponse().setBody(JSONObject().put("total_count", 5000).put("items", items).toString())) }
            server.start(); val source = GitHubDiscovery(OkHttpClient(), server.url("/"))
            assertTrue(source.search("android", 1).hasMore)
            assertFalse(source.search("android", 50).hasMore)
        }
    }
    @Test fun `rate limit errors are actionable and never become local search results`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403)); server.start()
            val error = runCatching { GitHubDiscovery(OkHttpClient(), server.url("/")).search("outside") }.exceptionOrNull()
            assertTrue(error!!.message!!.contains("请求过于频繁"))
        }
    }
    @Test fun `remote repository details combine releases and readme without inventing trust`() = runBlocking {
        MockWebServer().use { server ->
            val release = JSONObject().put("id", 1).put("tag_name", "v2").put("published_at", "2026-01-01T00:00:00Z")
                .put("html_url", "https://github.com/author/OutsideCatalog/releases/tag/v2").put("assets", JSONArray())
            val text = """<img src="art/logo.svg" alt="Project logo">

                |A useful application from a repository outside the original catalog. This content comes from README.

                |![Screenshot](screens/main.png)
                |""".trimMargin()
            server.enqueue(MockResponse().setBody(JSONArray(listOf(release)).toString()))
            server.enqueue(MockResponse().setBody(JSONObject().put("encoding", "base64").put("content", Base64.getEncoder().encodeToString(text.toByteArray()))
                .put("html_url", "https://github.com/author/OutsideCatalog/blob/main/README.md").put("path", "README.md").toString()))
            server.start()
            val app = GitHubDiscovery(OkHttpClient(), server.url("/")).details(GitHubDiscovery.repository(repo()))
            assertEquals("v2", app.releases.single().tag)
            assertEquals("https://raw.githubusercontent.com/author/OutsideCatalog/main/art/logo.svg", app.iconUrl)
            assertEquals("https://raw.githubusercontent.com/author/OutsideCatalog/main/screens/main.png", app.screenshots.single())
            assertTrue(app.description.contains("outside the original catalog"))
            assertTrue(app.certificateSha256.isEmpty()); assertEquals("", app.packageName)
        }
    }
    @Test fun `missing readme preserves real release data`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[]")); server.enqueue(MockResponse().setResponseCode(404)); server.start()
            val app = GitHubDiscovery.repository(repo())
            val result = GitHubDiscovery(OkHttpClient(), server.url("/")).details(app)
            assertEquals(app.description, result.description); assertEquals(app.iconUrl, result.iconUrl)
        }
    }
    @Test fun `readme excludes badges and private addresses while resolving nested relative media`() {
        val info = ReadmePresentation.extract("""![logo badge](https://shields.io/badge/test)
            |<img alt='logo' src='../../assets/icon.png'>
            |![Screenshot](../shots/one.png)
            |![logo](https://127.0.0.1/private.png)
            |""".trimMargin(), "owner/repo", "https://github.com/owner/repo/blob/main/docs/zh/README.md", "docs/zh/README.md")
        assertEquals("https://raw.githubusercontent.com/owner/repo/main/assets/icon.png", info.icon)
        assertEquals("https://raw.githubusercontent.com/owner/repo/main/docs/shots/one.png", info.screenshots.single())
        assertEquals("", ReadmePresentation.safeImage("http://example.com/logo.png"))
        assertEquals("", ReadmePresentation.safeImage("https://192.168.1.2/logo.png"))
    }
    @Test fun `reference images and script text are handled as display data only`() {
        val info = ReadmePresentation.extract("""![App logo][brand]
            |[brand]: https://example.com/icon.svg
            |<script>this must never appear in the introduction even if long enough to be a paragraph</script>
            |
            |The actual application introduction with enough text to be retained.
            |""".trimMargin(), "owner/repo", "https://github.com/owner/repo/blob/dev/README.md", "README.md")
        assertEquals("https://example.com/icon.svg", info.icon)
        assertFalse(info.description.contains("script")); assertTrue(info.description.contains("actual application"))
    }
    @Test fun `default version is newest stable independent of API ordering and explicit history is preserved`() {
        fun release(tag: String, time: String, pre: Boolean = false) = AppRelease(tag.hashCode().toLong(), tag, tag, time, "", pre, "", emptyList())
        val old = release("v1", "2025-01-01T00:00:00Z")
        val beta = release("v3beta", "2026-03-01T00:00:00Z", true)
        val latest = release("v2", "2026-01-01T00:00:00Z")
        assertEquals(latest, selectedRelease(listOf(old, beta, latest), null))
        assertEquals(old, selectedRelease(listOf(old, beta, latest), "v1"))
        assertEquals(beta, selectedRelease(listOf(beta), null))
        assertNull(selectedRelease(emptyList(), null))
    }
}
