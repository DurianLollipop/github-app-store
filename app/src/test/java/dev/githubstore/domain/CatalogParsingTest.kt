package dev.githubstore.domain

import dev.githubstore.data.CatalogRepository
import dev.githubstore.data.JsonCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogParsingTest {
    @Test fun `README metadata preserves source and remains compatible with older catalogs`() {
        val json = JSONObject("""{"id":"app","name":"App","repository":"a/b","packageName":"org.example.app","category":"工具","tagline":"简介","description":"README 正文","license":"MIT","color":"123456","symbol":"A","screenshots":["https://example.org/screen.png"]}""")
        assertEquals("", JsonCodec.readApp(json).readmeUrl)
        val updated = JsonCodec.readApp(json.put("readmeUrl", "https://github.com/a/b/blob/main/README.md")
            .put("iconUrl", "https://example.org/logo.svg"))
        assertEquals("https://github.com/a/b/blob/main/README.md", updated.readmeUrl)
        assertEquals("README 正文", updated.description)
        assertEquals(listOf("https://example.org/screen.png"), updated.screenshots)
        assertEquals("https://example.org/logo.svg", updated.iconUrl)
    }
    @Test fun `GitHub release preserves prerelease flag and only APK assets`() {
        val json = JSONObject("""{"id":42,"tag_name":"nightly-2026","name":"Preview","published_at":"2026-11-01T00:00:00Z","prerelease":true,"html_url":"https://github.com/a/b/releases/tag/nightly-2026","assets":[{"id":1,"name":"app.apk","browser_download_url":"https://github.com/a/b/releases/download/nightly-2026/app.apk","size":123,"digest":"sha256:${"a".repeat(64)}"},{"id":2,"name":"source.zip"}]}""")
        val release = CatalogRepository.parseGitHubRelease(json)
        assertTrue(release.prerelease); assertEquals(1, release.assets.size)
        assertEquals("a".repeat(64), release.assets.single().sha256)
        assertNull(release.assets.single().versionCode)
    }
    @Test fun `unknown package metadata survives round trip without becoming zero`() {
        val asset = ReleaseAsset(1, "app.apk", "https://example.org/app.apk", 123, null)
        val decoded = JsonCodec.readAsset(JsonCodec.asset(asset))
        assertNull(decoded.versionCode); assertNull(decoded.minSdk); assertNull(decoded.sha256)
    }
    @Test fun `download task roundtrip retains session and failure reason`() {
        val task = DownloadTask("task", "app", "App", "dev.app", "v1", ReleaseAsset(1, "app.apk", "https://example.org/a", 123, "a".repeat(64)),
            status = TaskStatus.FAILED, error = "用户取消", sessionId = 12)
        assertEquals(task, JsonCodec.readTask(JsonCodec.task(task)))
    }
}
