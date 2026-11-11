package dev.githubstore.domain

import dev.githubstore.data.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.EventListener
import okhttp3.Call
import okhttp3.Response
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class GitHubTransportTest {
    @Test fun `leaving during a slow response body releases foreground queue`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(64 * 1024)).throttleBody(1, 200, java.util.concurrent.TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody("{}")); server.start()
            val headers = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseHeadersEnd(call: Call, response: Response) { headers.complete(Unit) }
            }).build()
            val transport = GitHubTransport(client)
            val pending = launch { transport.get(server.url("/repos/a/slow-body")) }
            headers.await(); delay(30)
            withTimeout(2000) { pending.cancelAndJoin() }
            assertEquals("{}", withTimeout(2000) { transport.get(server.url("/repos/a/next")) })
        }
    }
    @Test fun `concurrent same read shares cache and ETag revalidation restores body on 304`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"name\":\"original\"}").setHeader("ETag", "\"v1\""))
            server.enqueue(MockResponse().setResponseCode(304))
            server.start(); var now = 100_000L
            val transport = GitHubTransport(OkHttpClient(), clock = { now })
            val url = server.url("/repos/owner/app/readme")
            val results = coroutineScope { (0..8).map { async { transport.get(url) } }.awaitAll() }
            assertEquals(1, server.requestCount); assertEquals(1, results.distinct().size)
            now += 2000
            val again = withContext(GitHubRequestPolicy(revalidate = true)) { transport.get(url) }
            assertEquals(results.first(), again)
            server.takeRequest(); assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
        }
    }
    @Test fun `background stops early while foreground can use reserved quota and exhausted core does not stop search`() = runBlocking {
        MockWebServer().use { server ->
            val reset = 500_000L
            server.enqueue(MockResponse().setBody("{}").setHeader("X-RateLimit-Remaining", 12).setHeader("X-RateLimit-Reset", reset / 1000))
            server.enqueue(MockResponse().setBody("{}").setHeader("X-RateLimit-Remaining", 0).setHeader("X-RateLimit-Reset", reset / 1000))
            server.enqueue(MockResponse().setBody("{\"items\":[]}"))
            server.start(); val transport = GitHubTransport(OkHttpClient(), clock = { 100_000L })
            transport.get(server.url("/repos/a/one"))
            val denied = runCatching { withContext(GitHubRequestPolicy(background = true)) { transport.get(server.url("/repos/a/two")) } }.exceptionOrNull()
            assertTrue(denied is GitHubRateLimitException); assertEquals(1, server.requestCount)
            transport.get(server.url("/repos/a/two"))
            assertTrue(runCatching { transport.get(server.url("/repos/a/three")) }.exceptionOrNull() is GitHubRateLimitException)
            transport.get(server.url("/search/repositories?q=tv")); assertEquals(3, server.requestCount)
            // Previously cached data remains readable even while its primary quota is exhausted.
            assertEquals("{}", transport.get(server.url("/repos/a/one")))
        }
    }
    @Test fun `permission 403 is distinct from rate limiting and is not cached`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("{\"message\":\"Resource not accessible\"}"))
            server.enqueue(MockResponse().setBody("{}")); server.start()
            val transport = GitHubTransport(OkHttpClient())
            val error = runCatching { transport.get(server.url("/repos/a/b")) }.exceptionOrNull()
            assertTrue(error is GitHubApiException); assertTrue(error!!.message!!.contains("拒绝访问"))
            assertEquals("{}", transport.get(server.url("/repos/a/b"))); assertEquals(2, server.requestCount)
        }
    }
    @Test fun `secondary limit honors retry after and is shared with search`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
            server.enqueue(MockResponse().setBody("{}")); server.start(); var now = 100_000L
            val transport = GitHubTransport(OkHttpClient(), clock = { now })
            val error = runCatching { transport.get(server.url("/repos/a/b")) }.exceptionOrNull() as GitHubRateLimitException
            assertEquals(220_000L, error.retryAt)
            assertTrue(runCatching { transport.get(server.url("/search/repositories?q=tv")) }.exceptionOrNull() is GitHubRateLimitException)
            assertEquals(1, server.requestCount)
            now = 220_001; transport.get(server.url("/repos/a/b")); assertEquals(2, server.requestCount)
        }
    }
    @Test fun `replacing credentials isolates cached responses and invalid token is cleared`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"name\":\"authorized\"}"))
            server.enqueue(MockResponse().setBody("{\"name\":\"guest\"}"))
            server.enqueue(MockResponse().setResponseCode(401)); server.start()
            var token: String? = "test-user-token"
            val transport = GitHubTransport(OkHttpClient(), { token }, unauthorized = { if (it == token) token = null })
            val url = server.url("/repos/a/b")
            assertTrue(transport.get(url).contains("authorized")); assertEquals("Bearer test-user-token", server.takeRequest().getHeader("Authorization"))
            token = null; assertTrue(transport.get(url).contains("guest")); assertNull(server.takeRequest().getHeader("Authorization"))
            token = "expired-token"; assertTrue(runCatching { transport.get(server.url("/repos/a/other")) }.exceptionOrNull() is GitHubApiException)
            assertNull(token)
        }
    }
    @Test fun `installation asset lookup always bypasses body cache`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"id\":1}")); server.enqueue(MockResponse().setResponseCode(404)); server.start()
            val transport = GitHubTransport(OkHttpClient()); val url = server.url("/repos/a/b/releases/assets/1")
            transport.get(url)
            assertTrue(runCatching { transport.get(url) }.exceptionOrNull() is GitHubApiException)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun `cancelling slow read cancels call and next foreground read proceeds`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.enqueue(MockResponse().setBody("{}")); server.start()
            val transport = GitHubTransport(OkHttpClient())
            val pending = launch { transport.get(server.url("/repos/a/slow")) }
            withContext(Dispatchers.IO) { server.takeRequest() }
            pending.cancelAndJoin()
            assertEquals("{}", withTimeout(3000) { transport.get(server.url("/repos/a/next")) })
        }
    }
    @Test fun `details and reader use same README response and history is lazy`() = runBlocking {
        MockWebServer().use { server ->
            val repo = "{\"id\":42,\"name\":\"app\",\"full_name\":\"a/app\"}"
            server.enqueue(MockResponse().setBody(repo))
            server.enqueue(MockResponse().setBody("{\"id\":1,\"tag_name\":\"v2\",\"html_url\":\"https://github.com/a/app/releases/tag/v2\",\"assets\":[]}"))
            server.enqueue(MockResponse().setBody("{\"path\":\"README.md\",\"html_url\":\"https://github.com/a/app/blob/main/README.md\",\"encoding\":\"base64\",\"content\":\"SGVsbG8=\"}"))
            server.enqueue(MockResponse().setBody("[{\"id\":2,\"tag_name\":\"v1\",\"html_url\":\"https://github.com/a/app/releases/tag/v1\",\"assets\":[]}]")); server.start()
            val github = GitHubDiscovery(OkHttpClient(), server.url("/"))
            val app = StoreApp("test", "app", "a/app", "", "", "", "", "MIT", 0L, "A", githubId = 42)
            val detailed = github.details(app); github.contents(detailed)
            assertEquals(3, server.requestCount)
            assertEquals("/repositories/42", server.takeRequest().path)
            assertEquals("/repos/a/app/releases/latest", server.takeRequest().path)
            assertEquals("v1", github.history(app).single().tag); assertEquals(4, server.requestCount)
        }
    }
}
