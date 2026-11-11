package dev.githubstore.domain

import dev.githubstore.download.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.*
import java.util.concurrent.TimeUnit

class NoDigestResumeTest {
    @get:Rule val folder = TemporaryFolder()
    private val content = ByteArray(256 * 1024) { (it * 11).toByte() }
    private fun run(server: MockWebServer, fail: Boolean = false, parallel: Boolean = false) = runBlocking {
        DownloadEngine(OkHttpClient.Builder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build(), false, if (parallel) 1 else Long.MAX_VALUE)
            .download("o/r", ReleaseAsset(8, "a.apk", server.url("/a.apk").toString(), content.size.toLong(), null, updatedAt = "2026-11-11"),
                File(folder.root, "a.part"), File(folder.root, "a.apk"), { if (fail && it >= 64 * 1024) throw IOException("offline") }, {})
    }
    private fun response(request: RecordedRequest, etag: String?): MockResponse {
        val range = request.getHeader("Range")?.removePrefix("bytes=")?.split('-')
        val start = range?.get(0)?.toInt() ?: 0
        val end = range?.get(1)?.toIntOrNull() ?: content.lastIndex
        return MockResponse().setHeader("Connection", "close").setBody(Buffer().write(content, start, end - start + 1)).apply {
            if (range != null) setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${content.size}")
            if (etag != null) setHeader("ETag", etag)
        }
    }
    @Test fun `strong validator resumes without upstream digest and calculates local identity`() {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() { override fun dispatch(request: RecordedRequest) = response(request, "\"v1\"").apply { if (request.getHeader("Range") == null) throttleBody(32 * 1024, 400, TimeUnit.MILLISECONDS) } }
            server.start(); assertTrue(runCatching { run(server, fail = true) }.isFailure)
            val offset = File(folder.root, "a.part").length(); server.takeRequest()
            run(server)
            val resumed = server.takeRequest()
            assertEquals("bytes=$offset-", resumed.getHeader("Range")); assertEquals("\"v1\"", resumed.getHeader("If-Range"))
            assertArrayEquals(content, File(folder.root, "a.apk").readBytes())
        }
    }
    @Test fun `missing strong validator restarts rather than mixing unverified resource versions`() {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() { override fun dispatch(request: RecordedRequest) = response(request, null).throttleBody(32 * 1024, 200, TimeUnit.MILLISECONDS) }
            server.start(); assertTrue(runCatching { run(server, fail = true) }.isFailure); server.takeRequest()
            run(server)
            assertNull(server.takeRequest().getHeader("Range")); assertArrayEquals(content, File(folder.root, "a.apk").readBytes())
        }
    }
    @Test fun `changed validator on a partial response fails before file becomes installable`() {
        MockWebServer().use { server ->
            var version = "\"v1\""
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() { override fun dispatch(request: RecordedRequest) = response(request, version).throttleBody(32 * 1024, 200, TimeUnit.MILLISECONDS) }
            server.start(); assertTrue(runCatching { run(server, fail = true) }.isFailure)
            version = "\"v2\""
            assertTrue(runCatching { run(server) }.isFailure)
            assertFalse(File(folder.root, "a.apk").exists()); assertFalse(File(folder.root, "a.part").exists())
        }
    }
    @Test fun `parallel transfer without digest requires matching strong validators on each part`() {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() { override fun dispatch(request: RecordedRequest) = response(request, if (request.getHeader("Range") == "bytes=0-0") "\"v1\"" else "\"v2\"") }
            server.start(); assertTrue(runCatching { run(server, parallel = true) }.isFailure)
            assertFalse(File(folder.root, "a.apk").exists())
        }
    }
}
