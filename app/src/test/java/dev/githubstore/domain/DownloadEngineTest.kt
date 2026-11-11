package dev.githubstore.domain

import dev.githubstore.download.DownloadEngine
import dev.githubstore.download.sha256
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class DownloadEngineTest {
    @get:Rule val folder = TemporaryFolder()
    private val content = "a real download payload".toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    private fun withServer(block: (MockWebServer) -> Unit) { MockWebServer().use { server -> server.start(); block(server) } }
    private fun asset(server: MockWebServer, checksum: String? = digest, size: Long = content.size.toLong()) =
        ReleaseAsset(1, "app.apk", server.url("/app.apk").toString(), size, checksum)

    @Test fun `valid file is finalized only after full hash verification`() = withServer { server ->
        server.enqueue(MockResponse().setBody(String(content)))
        val partial = folder.newFile("valid.part"); val output = java.io.File(folder.root, "valid.apk")
        var verified = false
        runBlocking { DownloadEngine(OkHttpClient(), false).download("a/b", asset(server), partial, output, {}, { verified = true }) }
        assertTrue(verified); assertTrue(output.exists()); assertFalse(partial.exists()); assertEquals(digest, sha256(output))
    }
    @Test fun `corrupt hash removes partial and never creates installable APK`() = withServer { server ->
        server.enqueue(MockResponse().setBody(String(content)))
        val partial = folder.newFile("bad.part"); val output = java.io.File(folder.root, "bad.apk")
        val result = runCatching { runBlocking { DownloadEngine(OkHttpClient(), false).download("a/b", asset(server, "f".repeat(64)), partial, output, {}, {}) } }
        assertTrue(result.isFailure); assertFalse(partial.exists()); assertFalse(output.exists())
    }
    @Test fun `changed content length fails closed`() = withServer { server ->
        server.enqueue(MockResponse().setBody("a different size"))
        val partial = folder.newFile("size.part"); val output = java.io.File(folder.root, "size.apk")
        val result = runCatching { runBlocking { DownloadEngine(OkHttpClient(), false).download("a/b", asset(server), partial, output, {}, {}) } }
        assertTrue(result.isFailure); assertFalse(output.exists())
    }
    @Test fun `deleted release produces actionable error`() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(404))
        val result = runCatching { runBlocking { DownloadEngine(OkHttpClient(), false).download("a/b", asset(server), folder.newFile("404.part"), java.io.File(folder.root, "404.apk"), {}, {}) } }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("移除"))
    }
    @Test fun `missing upstream digest downloads file without claiming upstream verification`() = withServer { server ->
        server.enqueue(MockResponse().setBody(String(content)))
        val output = java.io.File(folder.root, "missing.apk")
        runBlocking { DownloadEngine(OkHttpClient(), false).download("a/b", asset(server, null), folder.newFile("missing.part"), output, {}, {}) }
        assertEquals(digest, sha256(output)); assertEquals(1, server.requestCount)
    }
    @Test fun `production origin policy rejects arbitrary URLs before transfer`() = withServer { server ->
        val result = runCatching { runBlocking { DownloadEngine(OkHttpClient()).download("a/b", asset(server), folder.newFile("origin.part"), java.io.File(folder.root, "origin.apk"), {}, {}) } }
        assertTrue(result.isFailure); assertEquals(0, server.requestCount)
    }
}
