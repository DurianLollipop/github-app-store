package dev.githubstore

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.githubstore.data.*
import dev.githubstore.domain.*
import dev.githubstore.readme.*
import dev.githubstore.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ToolWorkflowTest {
    @get:Rule val rule = createComposeRule()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun app() = StoreApp("outside-300", "Fixture Project", "fixture/project", "", "工具", "", "", "MIT", 0xFF168A7F, "F", githubId = 300, defaultBranch = "main")
    private fun isolated(block: (Context) -> Unit) {
        val id = "tool-${System.nanoTime()}"
        val dir = File(base.cacheDir, id).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = dir
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$id-$name", mode)
        }
        try { block(context) } finally { dir.deleteRecursively() }
    }
    @Test fun githubAuthorizationIsEncryptedPersistedAndDeletedOnDisconnect() = isolated { context ->
        val auth = GitHubAuthStore(context)
        auth.configure("public-fixture-client")
        runBlocking { auth.save(DeviceGrant("fixture-private-token", "fixture-user", 0)) }
        val stored = context.getSharedPreferences("github-auth", 0).getString("grant", null)!!
        assertFalse(stored.contains("fixture-private-token")); assertFalse(stored.contains("fixture-user"))
        val restarted = GitHubAuthStore(context)
        assertEquals("fixture-private-token", restarted.token()); assertEquals("fixture-user", restarted.state.value.login)
        restarted.invalidate("some-other-token"); assertEquals("fixture-private-token", restarted.token())
        restarted.disconnect(); assertNull(restarted.token()); assertNull(GitHubAuthStore(context).token())
        assertEquals("public-fixture-client", restarted.state.value.clientId)
    }
    @Test fun favoritesSubscriptionsAndBindingsSurviveRestartIndependently() = isolated { context ->
        val library = UserLibrary(context); val app = app()
        library.remember(app)
        library.mutate(app.id) { it!!.copy(favorite = true, subscribed = true) }
        val identity = InstalledApp("fixture.package", "1", 1, setOf("a".repeat(64)))
        library.bind(app, identity, "stable-arm64", "v1")
        library.mutate(app.id) { it!!.copy(subscribed = false) }
        val loaded = UserLibrary(context).find(app.id)!!
        assertTrue(loaded.favorite); assertFalse(loaded.subscribed); assertTrue(loaded.installChecks)
        assertEquals(identity, loaded.bindings.single().identity)
        library.mutate(app.id) { it!!.copy(bindings = emptyList()) }
        assertTrue(UserLibrary(context).find(app.id)!!.favorite)
        assertTrue(UserLibrary(context).find(app.id)!!.bindings.isEmpty())
    }
    @Test fun discoverySummaryCannotEraseDetailedMetadataOrCreateDuplicateUserRecord() = isolated { context ->
        val library = UserLibrary(context)
        val detailed = app().copy(description = "完整介绍", checkedAt = 1000, readmeUrl = "https://github.com/fixture/project/blob/main/README.md")
        library.remember(detailed)
        library.mutate(detailed.id) { it!!.copy(favorite = true, subscribed = true) }
        val merged = library.remember(detailed.copy(id = "trend-another-id", description = "摘要", checkedAt = 0, stars = 800))
        assertEquals(detailed.id, merged.id); assertEquals("完整介绍", merged.description)
        val restored = UserLibrary(context).records.value
        assertEquals(1, restored.size); assertTrue(restored.single().favorite); assertTrue(restored.single().subscribed)
    }
    @Test fun longReadmeParseAndCacheHaveRecordedEmulatorTimings() = isolated { context ->
        val repository = ReadmeRepository(context) { GitHubDiscovery(okhttp3.OkHttpClient()) }
        val text = ("A readable paragraph with a [link](#title). ".repeat(20) + "\n\n").repeat(130)
        val file = ReadmeFile("README.md", "main", text, "https://github.com/fixture/project/blob/main/README.md", 1, "perf-fixture")
        val start = System.nanoTime()
        val first = runBlocking { repository.blocks(app(), file) }
        val parsed = System.nanoTime()
        val second = runBlocking { repository.blocks(app(), file) }
        val cached = System.nanoTime()
        assertSame(first, second); assertTrue(first.size > 100)
        val result = JSONObject().put("fixtureBytes", text.toByteArray().size).put("parseMs", (parsed-start)/1_000_000.0).put("cachedMs", (cached-parsed)/1_000_000.0)
        File(base.getExternalFilesDir(null), "readme-performance.json").writeText(result.toString())
    }
    @Test fun agreementRequiresExplicitAcceptanceAndPersistsVersion() = isolated { context ->
        assertFalse(AgreementStore(context).accepted)
        rule.setContent { OrbitTheme { AgreementPanel(false, { AgreementStore(context).accept() }, {}) } }
        rule.onNodeWithTag("agreement-accept").assertIsDisplayed().assertIsNotFocused()
        assertFalse(AgreementStore(context).accepted)
        rule.onNodeWithTag("agreement-accept").performClick()
        assertTrue(AgreementStore(context).accepted)
        assertTrue(context.getSharedPreferences("user-agreement", 0).getLong("acceptedAt", 0) > 0)
    }
    @Test fun realLocalApkCanBeInspectedWithoutCuratedCertificateAndWrongPackageIsRejected() = isolated { context ->
        val file = File(base.applicationInfo.sourceDir)
        val asset = ReleaseAsset(1, "fixture.apk", "https://github.com/fixture/project/releases/download/v1/fixture.apk", file.length(), null)
        val inspector = dev.githubstore.install.PackageInspector(context)
        val info = inspector.verify(app(), asset, file)
        assertEquals(base.packageName, info.packageName)
        assertFalse(info.certificates.isEmpty())
        assertTrue(runCatching { inspector.verify(app().copy(packageName = "wrong.package"), asset, file) }.isFailure)
        assertTrue(runCatching { inspector.verify(app(), asset.copy(sha256 = "f".repeat(64)), file) }.isFailure)
    }
    @Test fun languageSwitchUsesRealReadmeFilesAndRemembersOnlySuccessfulSelection() = isolated { context ->
        val app = app()
        val contents = mutableListOf<String?>()
        val github = object : GitHubDiscovery(okhttp3.OkHttpClient()) {
            override suspend fun contents(app: StoreApp, path: String?): JSONObject {
                contents += path
                val actual = path ?: "README.md"
                val text = if (actual == "README.zh-CN.md") "# 中文介绍\n\n这是仓库实际提供的中文版本。" else "# English\n\n[中文](README.zh-CN.md)\n\nOriginal README content."
                return JSONObject().put("path", actual).put("encoding", "base64").put("type", "file")
                    .put("content", Base64.getEncoder().encodeToString(text.toByteArray())).put("sha", actual)
                    .put("html_url", "https://github.com/fixture/project/blob/main/$actual")
            }
            override suspend fun directory(app: StoreApp, path: String) = listOf("README.md", "README.zh-CN.md")
        }
        val repo = ReadmeRepository(context) { github }
        rule.setContent { OrbitTheme { ReadmeReader(app, repo, {}, {}) } }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("readme-language").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(contents.contains("README.zh-CN.md"))
        rule.onNodeWithTag("readme-language").performClick()
        rule.onNodeWithTag("readme-language-README.zh-CN.md").performClick()
        rule.waitUntil(5000) { rule.onAllNodes(hasText("这是仓库实际提供的中文版本。", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("README.zh-CN.md", ReadmeRepository(context) { github }.preferred(app))
        assertTrue(contents.contains("README.zh-CN.md"))
        val offline = object : GitHubDiscovery(okhttp3.OkHttpClient()) {
            override suspend fun contents(app: StoreApp, path: String?): JSONObject = error("offline")
        }
        val restored = runBlocking { ReadmeRepository(context) { offline }.load(app, "README.zh-CN.md") }
        assertTrue(restored.text.contains("中文版本")); assertTrue(restored.cached)
    }
    @Test fun remoteCanReadLongMarkdownUntilFinalParagraph() = isolated { context ->
        val text = "# 长文阅读\n\n" + (1..45).joinToString("\n\n") { "第 $it 段：这是通过遥控器阅读的实际正文。" } + "\n\nREADME_FINAL_PARAGRAPH"
        val github = object : GitHubDiscovery(okhttp3.OkHttpClient()) {
            override suspend fun contents(app: StoreApp, path: String?) = JSONObject().put("path", "README.md").put("encoding", "base64")
                .put("content", Base64.getEncoder().encodeToString(text.toByteArray())).put("html_url", "https://github.com/fixture/project/blob/main/README.md")
            override suspend fun directory(app: StoreApp, path: String) = listOf("README.md")
        }
        rule.setContent { OrbitTheme { ReadmeReader(app(), ReadmeRepository(context) { github }, {}, {}) } }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("readme-block-0").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("readme-block-0").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        var attempts = 0
        while (!rule.onNodeWithText("README_FINAL_PARAGRAPH").isDisplayed() && attempts++ < 70) {
            rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        }
        rule.onNodeWithText("README_FINAL_PARAGRAPH").assertIsDisplayed()
    }
}
