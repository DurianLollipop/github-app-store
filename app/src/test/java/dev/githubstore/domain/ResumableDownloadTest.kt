package dev.githubstore.domain

import dev.githubstore.download.DownloadEngine
import dev.githubstore.download.sha256
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ResumableDownloadTest {
    @get:Rule val folder = TemporaryFolder()
    private val content = ByteArray(512 * 1024) { (it * 31).toByte() }
    private val digest = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private fun asset(server: MockWebServer) = ReleaseAsset(1, "app.apk", server.url("/app.apk").toString(), content.size.toLong(), digest)
    private fun client() = OkHttpClient.Builder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()
    private fun output() = File(folder.root, "app.apk")
    private fun partial() = File(folder.root, "app.part")
    private fun download(server: MockWebServer, parallel: Boolean = false, progress: (Long) -> Unit = {}) = runBlocking {
        DownloadEngine(client(), false, if (parallel) 1 else Long.MAX_VALUE)
            .download("a/b", asset(server), partial(), output(), progress, {})
    }
    private fun rangeResponse(request: RecordedRequest): MockResponse {
        val range = request.getHeader("Range") ?: return MockResponse().setBody(Buffer().write(content)).setHeader("ETag", "\"v1\"")
        val (startText, endText) = range.removePrefix("bytes=").split('-')
        val start = startText.toInt()
        val end = endText.toIntOrNull() ?: content.lastIndex
        return MockResponse().setHeader("Connection", "close").setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${content.size}")
            .setHeader("ETag", "\"v1\"").setBody(Buffer().write(content, start, end - start + 1))
    }

    @Test fun `interrupted sequential transfer survives a new engine and resumes exact offset`() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = rangeResponse(request).apply {
                    if (request.getHeader("Range") == null) throttleBody(32 * 1024, 400, TimeUnit.MILLISECONDS)
                }
            }
            server.start()
            assertTrue(runCatching { download(server) { if (it >= 64 * 1024) throw IOException("offline") } }.isFailure)
            val offset = partial().length()
            assertTrue(offset in 1 until content.size.toLong())
            server.takeRequest()
            download(server)
            val resumed = server.takeRequest()
            assertEquals("bytes=$offset-", resumed.getHeader("Range"))
            assertEquals("\"v1\"", resumed.getHeader("If-Range"))
            assertEquals(digest, sha256(output()))
            assertFalse(DownloadEngine.checkpointDir(partial()).exists())
        }
    }
    @Test fun `four parts overlap on the server and assemble byte for byte`() {
        MockWebServer().use { server ->
            val allParts = CountDownLatch(4)
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Range") != "bytes=0-0") {
                        val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }
                        allParts.countDown()
                        allParts.await(3, TimeUnit.SECONDS)
                        active.decrementAndGet()
                    }
                    return rangeResponse(request)
                }
            }
            server.start(); download(server, true)
            assertEquals(4, maximum.get()); assertEquals(5, server.requestCount)
            assertEquals(digest, sha256(output()))
        }
    }
    @Test fun `parallel interruption retains independent offsets across process restart`() {
        MockWebServer().use { server ->
            val requests = CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.getHeader("Range").orEmpty()
                    return rangeResponse(request).apply {
                        if (request.getHeader("Range") != "bytes=0-0" && requests.size <= 5)
                            throttleBody(32 * 1024, 400, TimeUnit.MILLISECONDS)
                    }
                }
            }
            server.start()
            assertTrue(runCatching { download(server, true) { if (it > 0) throw IOException("offline") } }.isFailure)
            val lengths = (0..3).map { File(DownloadEngine.checkpointDir(partial()), "$it.chunk").length() }
            assertTrue(lengths.sum() > 0)
            requests.clear(); download(server, true)
            for (index in 0..3) {
                val start = content.size * index / 4L
                val end = content.size * (index + 1) / 4L - 1
                val expected = "bytes=${start + lengths[index]}-$end"
                if (start + lengths[index] <= end) assertTrue(requests.contains(expected))
            }
            assertEquals(digest, sha256(output()))
        }
    }
    @Test fun `server ignoring resume range restarts cleanly instead of appending`() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(Buffer().write(content))
            }
            server.start()
            assertTrue(runCatching { download(server) { if (it > 0) throw IOException("offline") } }.isFailure)
            download(server)
            assertEquals(digest, sha256(output()))
        }
    }
    @Test fun `server without range support falls back to a full single connection`() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(Buffer().write(content))
            }
            server.start(); download(server, true)
            assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
            assertNull(server.takeRequest().getHeader("Range"))
            assertEquals(digest, sha256(output()))
        }
    }
    @Test fun `ignored chunk range after successful probe falls back safely`() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.getHeader("Range") == "bytes=0-0") rangeResponse(request)
                    else MockResponse().setHeader("Connection", "close").setBody(Buffer().write(content))
            }
            server.start(); download(server, true)
            assertEquals(digest, sha256(output()))
        }
    }
    @Test fun `lost range support clears old chunks even if fallback transfer disconnects`() {
        MockWebServer().use { server ->
            var rangeSupported = true
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return if (rangeSupported) rangeResponse(request).apply {
                        if (request.getHeader("Range") != "bytes=0-0") throttleBody(32 * 1024, 400, TimeUnit.MILLISECONDS)
                    } else MockResponse().setHeader("Connection", "close").setBody(Buffer().write(content))
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                }
            }
            server.start()
            assertTrue(runCatching { download(server, true) { if (it > 0) throw IOException("offline") } }.isFailure)
            assertTrue(DownloadEngine.checkpointDir(partial()).listFiles()!!.any { it.name.endsWith(".chunk") })
            rangeSupported = false
            assertTrue(runCatching { download(server, true) }.isFailure)
            assertFalse(DownloadEngine.checkpointDir(partial()).listFiles()!!.any { it.name.endsWith(".chunk") })
            assertEquals(partial().length(), DownloadEngine.persistedBytes(partial()))
        }
    }
    @Test fun `invalid content range removes checkpoint and never creates APK`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 1-1/${content.size}").setBody("x"))
            server.start()
            assertTrue(runCatching { download(server, true) }.isFailure)
            assertFalse(output().exists()); assertFalse(DownloadEngine.checkpointDir(partial()).exists())
        }
    }
    @Test fun `changed asset identity discards stale partial before making request`() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = rangeResponse(request) }
            server.start()
            assertTrue(runCatching { download(server) { if (it > 0) throw IOException("offline") } }.isFailure)
            server.takeRequest()
            val changed = asset(server).copy(sha256 = "f".repeat(64))
            runCatching { runBlocking { DownloadEngine(client(), false).download("a/b", changed, partial(), output(), {}, {}) } }
            assertNull(server.takeRequest().getHeader("Range"))
            assertFalse(output().exists()); assertFalse(partial().exists())
        }
    }
    @Test fun `socket disconnect preserves bytes and next request resumes instead of restarting`() {
        MockWebServer().use { server ->
            val first = java.util.concurrent.atomic.AtomicBoolean(true)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = rangeResponse(request).apply {
                    if (first.getAndSet(false)) setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                }
            }
            server.start()
            assertTrue(runCatching { download(server) }.isFailure)
            val offset = partial().length()
            assertTrue(offset in 1 until content.size.toLong())
            server.takeRequest(); download(server)
            assertEquals("bytes=$offset-", server.takeRequest().getHeader("Range"))
            assertEquals(digest, sha256(output()))
        }
    }
    @Test fun `changed validator discards all old chunks before requesting new ranges`() {
        MockWebServer().use { server ->
            var changed = false
            val ranges = CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    ranges += request.getHeader("Range").orEmpty()
                    return rangeResponse(request).apply {
                        setHeader("ETag", if (changed) "\"v2\"" else "\"v1\"")
                        if (!changed && request.getHeader("Range") != "bytes=0-0")
                            throttleBody(32 * 1024, 400, TimeUnit.MILLISECONDS)
                    }
                }
            }
            server.start()
            assertTrue(runCatching { download(server, true) { if (it > 0) throw IOException("offline") } }.isFailure)
            assertTrue(DownloadEngine.checkpointDir(partial()).listFiles()!!.any { it.name.endsWith(".chunk") && it.length() > 0 })
            changed = true; ranges.clear(); download(server, true)
            for (index in 0..3) assertTrue(ranges.contains("bytes=${content.size * index / 4}-${content.size * (index + 1) / 4 - 1}"))
            assertEquals(digest, sha256(output()))
        }
    }
    @Test fun `corrupt parallel chunk is rejected by final hash and all progress is discarded`() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val response = rangeResponse(request)
                    if (request.getHeader("Range") != "bytes=0-0") {
                        val range = request.getHeader("Range")!!.removePrefix("bytes=").split('-')
                        val size = range[1].toInt() - range[0].toInt() + 1
                        response.setBody(Buffer().write(ByteArray(size) { 1 }))
                    }
                    return response
                }
            }
            server.start()
            assertTrue(runCatching { download(server, true) }.isFailure)
            assertFalse(output().exists()); assertFalse(partial().exists())
            assertFalse(DownloadEngine.checkpointDir(partial()).exists())
        }
    }
    @Test fun `explicit cancellation closes transfer while retaining restartable bytes`() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = rangeResponse(request) }
            server.start()
            val engine = DownloadEngine(client(), false)
            assertTrue(runCatching { runBlocking {
                engine.download("a/b", asset(server), partial(), output(), { if (it > 0) engine.cancel() }, {})
            } }.isFailure)
            assertFalse(output().exists()); assertTrue(partial().length() > 0)
            download(server); assertEquals(digest, sha256(output()))
        }
    }
}
