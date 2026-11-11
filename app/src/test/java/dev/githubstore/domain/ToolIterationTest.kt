package dev.githubstore.domain

import dev.githubstore.data.*
import dev.githubstore.readme.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ToolIterationTest {
    private fun file(text: String) = ReadmeFile("docs/README.md", "main", text, "https://github.com/o/r/blob/main/docs/README.md", 1)
    @Test fun `full markdown keeps headings formatted spans table code links and end`() {
        val text = """# Hello World

**Bold** and *italic* with [link](other.md) and `code`.

| Name | Value |
| --- | --- |
| A | B |

```kotlin
val example = 1
```

END OF DOCUMENT
"""
        val blocks = MarkdownDocument.parse("o/r", file(text))
        assertEquals("hello-world", blocks.first().anchor)
        assertTrue(blocks.any { it.runs.any { r -> r.bold && r.text == "Bold" } })
        assertTrue(blocks.any { it.runs.any { r -> r.italic && r.text == "italic" } })
        assertTrue(blocks.any { it.runs.any { r -> r.href == "https://github.com/o/r/blob/main/docs/other.md" } })
        assertTrue(blocks.any { it.kind == "table" && "A" in it.text })
        assertTrue(blocks.any { it.kind == "code" && "example" in it.text })
        assertEquals("END OF DOCUMENT", blocks.last().text.trim())
    }
    @Test fun `long paragraphs split without losing unicode or trailing content`() {
        val text = "你好🌍".repeat(1000)
        val blocks = MarkdownDocument.parse("o/r", file(text))
        assertTrue(blocks.size > 10)
        assertTrue(blocks.all { it.text.length <= 360 })
        assertEquals(text, blocks.joinToString("") { it.text })
    }
    @Test fun `html scripts never reach reader and images remain separate blocks`() {
        val blocks = MarkdownDocument.parse("o/r", file("<script>danger()</script>\n\n<img src='../image.svg' alt='Logo'>\n\n<details><summary>More</summary><p>Hidden text</p></details>"))
        assertFalse(blocks.any { "danger" in it.text })
        assertEquals("https://raw.githubusercontent.com/o/r/main/image.svg", blocks.first { it.kind == "image" }.url)
        assertTrue(blocks.any { it.kind == "details" })
        assertTrue(blocks.any { it.group != 0 && "Hidden text" in it.text })
    }
    @Test fun `unsafe media and executable links are not navigable`() {
        assertEquals("", MarkdownDocument.resolve("o/r", file(""), "javascript:alert(1)"))
        assertEquals("", MarkdownDocument.resolve("o/r", file(""), "https://127.0.0.1/x", true))
        assertEquals("#section", MarkdownDocument.resolve("o/r", file(""), "#section"))
    }
    @Test fun `github attachment images retain original host instead of becoming invalid raw urls`() {
        val url = "https://github.com/user-attachments/assets/111-222"
        assertEquals(url, MarkdownDocument.resolve("o/r", file(""), url, true))
        assertEquals("https://raw.githubusercontent.com/o/r/main/docs/image.png", MarkdownDocument.resolve("o/r", file(""), "image.png", true))
    }
    @Test fun `oversize readme fails explicitly`() {
        assertTrue(runCatching { MarkdownDocument.parse("o/r", file("a".repeat(MarkdownDocument.MAX_BYTES + 1))) }.isFailure)
    }
    @Test fun `actual language files recognized while regular documents excluded`() {
        assertEquals("简体中文", MarkdownDocument.languageLabel("README.zh-CN.md"))
        assertEquals("English", MarkdownDocument.languageLabel("docs/README_en.md"))
        assertEquals("仓库默认", MarkdownDocument.languageLabel("README.md"))
        assertNull(MarkdownDocument.languageLabel("docs/INSTALL.md"))
        assertNull(MarkdownDocument.languageLabel("README.install.md"))
    }
    private fun item(id: String, stars: Long, growth: Long?) = DiscoveryItem(StoreApp(id, id, "o/$id", "", "", "", "", "MIT", 0xFF000000, id, stars = stars), growth)
    @Test fun `growth ranking rewards actual recent stars instead of total count`() {
        val a = item("A", 100_000, 20); val b = item("B", 2000, 800)
        assertEquals(listOf("B", "A"), DiscoveryRules.sort(listOf(a, b), HotSort.GROWTH).map { it.app.id })
        assertEquals(listOf("A", "B"), DiscoveryRules.sort(listOf(a, b), HotSort.TOTAL).map { it.app.id })
    }
    @Test fun `unknown growth is distinct from zero and dedup is stable`() {
        val a = item("A", 1000, null); val b = item("B", 1, 0)
        assertEquals(listOf("B"), DiscoveryRules.sort(listOf(a, b, b), HotSort.GROWTH).map { it.app.id })
        assertEquals(2, DiscoveryRules.sort(listOf(a, b), HotSort.TOTAL).size)
    }
    @Test fun `trending parses published period counts and never substitutes total`() {
        val html = """<article class="Box-row"><h2><a href="/o/project">o / project</a></h2><p>A project</p><a href="/o/project/stargazers">100,000</a><span>800 stars this week</span></article>"""
        val result = DiscoveryRepository.parseTrending(html, HotPeriod.WEEK).single()
        assertEquals(100_000L, result.app.stars); assertEquals(800L, result.growth)
        assertEquals("本周", result.window)
        assertNull(DiscoveryRepository.parseTrending(html.replace("800 stars this week", "Not available"), HotPeriod.WEEK).single().growth)
    }
    @Test fun `period aggregate returns unknown for missing days and keeps actual zero`() {
        val week = LocalDate.of(2026, 11, 8).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()
        val row = JSONObject().put("week", week).put("days", JSONArray(listOf(1, 2, 3, 0, 0, 0, 0)))
        assertEquals(6L, DiscoveryRules.aggregate(JSONArray(listOf(row)), HotPeriod.WEEK, LocalDate.of(2026, 11, 11)))
        assertEquals(0L, DiscoveryRules.aggregate(JSONArray(listOf(row)), HotPeriod.DAY, LocalDate.of(2026, 11, 11)))
        assertNull(DiscoveryRules.aggregate(JSONArray(listOf(row)), HotPeriod.MONTH, LocalDate.of(2026, 11, 11)))
    }
    @Test fun `new app metadata round trips without creating package trust`() {
        val app = item("one", 30, 5).app.copy(githubId = 100, topics = listOf("android-tv"), defaultBranch = "release/next")
        assertEquals(app, JsonCodec.readApp(JsonCodec.app(app)))
    }
    @Test fun `old task missing identity is not automatically source confirmed`() {
        val asset = ReleaseAsset(1, "a.apk", "https://github.com/o/r/releases/download/v1/a.apk", 10, null)
        val task = DownloadTask("old", "a", "A", "", "v1", asset, TaskStatus.READY)
        val encoded = JsonCodec.task(task).apply { remove("repository"); remove("inspected"); remove("localSha256"); remove("sourceConfirmed") }
        val restored = JsonCodec.readTask(encoded)
        assertFalse(restored.sourceConfirmed); assertNull(restored.inspected); assertNull(restored.localSha256)
    }
    @Test fun `matching already installed package is not proof this session succeeded`() {
        val info = InstalledApp("fixture.app", "1", 1, setOf("certificate"))
        val asset = ReleaseAsset(1, "a.apk", "https://github.com/o/r/releases/download/v1/a.apk", 10, null)
        val task = DownloadTask("t", "a", "A", info.packageName, "v1", asset, TaskStatus.WAITING_USER, inspected = info)
        assertFalse(task.matchesInstalled(info))
        assertTrue(task.copy(installSucceeded = true).matchesInstalled(info))
        assertFalse(task.copy(installSucceeded = true).matchesInstalled(info.copy(versionCode = 2)))
    }
    @Test fun `request budget is shared and cannot be exceeded`() {
        val budget = RequestBudget(2); budget.consume(); budget.consume()
        assertTrue(runCatching { budget.consume() }.isFailure)
    }
}
