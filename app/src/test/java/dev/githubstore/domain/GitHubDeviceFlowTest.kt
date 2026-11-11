package dev.githubstore.domain

import dev.githubstore.data.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class GitHubDeviceFlowTest {
    private val id = "public-client-id"
    private fun code(expires: Long = 1_000_000L) = DeviceCode("device-secret", "ABCD-EFGH", "https://github.com/login/device", expires, 5000)
    @Test fun `public client device grant honors slow down and validates identity without app secret`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"device_code\":\"device-secret\",\"user_code\":\"ABCD-EFGH\",\"verification_uri\":\"https://github.com/login/device\",\"expires_in\":900,\"interval\":5}"))
            server.enqueue(MockResponse().setBody("{\"error\":\"authorization_pending\"}"))
            server.enqueue(MockResponse().setBody("{\"error\":\"slow_down\"}"))
            server.enqueue(MockResponse().setBody("{\"access_token\":\"fixture-token\",\"scope\":\"\",\"expires_in\":3600}"))
            server.enqueue(MockResponse().setBody("{\"login\":\"fixture-user\"}")); server.start()
            var now = 100_000L; val waits = mutableListOf<Long>()
            val flow = GitHubDeviceFlow(OkHttpClient(), server.url("/"), server.url("/"), { now }, { waits += it; now += it })
            val pending = flow.begin(id); val grant = flow.finish(id, pending)
            assertEquals(listOf(5000L, 5000L, 10_000L), waits)
            assertEquals("fixture-user", grant.login); assertEquals(now + 3600_000, grant.expiresAt)
            val first = server.takeRequest().body.readUtf8(); assertTrue(first.contains("client_id=")); assertFalse(first.contains("client_secret")); assertFalse(first.contains("scope="))
            repeat(3) { val request = server.takeRequest(); assertFalse(request.body.readUtf8().contains("client_secret")); assertNull(request.getHeader("Authorization")) }
            assertEquals("Bearer fixture-token", server.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun `denial and expiration never produce a token`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"error\":\"access_denied\"}")); server.start()
            var now = 100_000L
            val flow = GitHubDeviceFlow(OkHttpClient(), server.url("/"), server.url("/"), { now }, { now += it })
            assertTrue(runCatching { flow.finish(id, code()) }.exceptionOrNull()!!.message!!.contains("取消"))
            assertTrue(runCatching { flow.finish(id, code(now + 2000)) }.exceptionOrNull()!!.message!!.contains("过期"))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `unexpected authorization host and excessive scopes are rejected`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"verification_uri\":\"https://example.com/device\"}"))
            server.enqueue(MockResponse().setBody("{\"access_token\":\"unsafe-grant\",\"scope\":\"repo\"}")); server.start()
            val flow = GitHubDeviceFlow(OkHttpClient(), server.url("/"), server.url("/"), { 100_000L }, {})
            assertTrue(runCatching { flow.begin(id) }.exceptionOrNull()!!.message!!.contains("授权地址"))
            assertTrue(runCatching { flow.finish(id, code()) }.exceptionOrNull()!!.message!!.contains("超出公开读取"))
            assertEquals(2, server.requestCount) // No /user request with an over-privileged grant.
        }
    }
    @Test fun `closing device flow cancels polling without further token requests`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val waiting = CompletableDeferred<Unit>()
            val flow = GitHubDeviceFlow(OkHttpClient(), server.url("/"), server.url("/"), { 100_000L }, { waiting.complete(Unit); awaitCancellation() })
            val job = launch { flow.finish(id, code()) }; waiting.await(); job.cancelAndJoin()
            assertEquals(0, server.requestCount)
        }
    }
}
