package dev.githubstore

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.githubstore.domain.*
import dev.githubstore.ui.AppDetail
import dev.githubstore.ui.OrbitTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VersionSelectionTest {
    @get:Rule val rule = createComposeRule()
    private var downloaded: Pair<String, Long>? = null
    private fun release(id: Long, date: String, pre: Boolean = false) = AppRelease(id, "v$id", "Version $id", date,
        "Only notes for version $id", pre, "https://github.com/example/player/releases/tag/v$id",
        listOf(ReleaseAsset(id, "v$id.apk", "https://github.com/example/player/releases/download/v$id/app.apk", id * 1024, "a".repeat(64))))
    private val app = StoreApp("versions-test", "版本测试", "example/player", "example.player", "播放器", "版本选择", "应用介绍",
        "MIT", 0xFF4569A6, "V", certificateSha256 = setOf("a".repeat(64)), releases = listOf(
            release(1, "2024-01-01T00:00:00Z"), release(3, "2026-01-01T00:00:00Z", true), release(2, "2025-01-01T00:00:00Z")))
    private fun show(value: StoreApp = app, busy: Boolean = false) {
        rule.setContent { OrbitTheme { Box(Modifier.fillMaxSize()) {
            AppDetail(value, null, busy, {}, {}, { r, a -> downloaded = r.tag to a.id }, {}, {}, {})
        } } }
    }
    @Test fun defaultsToNewestStableWithHistoryCollapsed() {
        show()
        rule.onNodeWithTag("version-picker").assertTextContains("最新：v2 ▾")
        rule.onNodeWithTag("version-menu").assertDoesNotExist()
        rule.onNodeWithTag("version-option-v1").assertDoesNotExist()
        rule.onNodeWithTag("detail-primary-download").performClick()
        assertEquals("v2" to 2L, downloaded)
    }
    @Test fun remoteCanChooseHistoricalReleaseAndReturnToLatest() {
        show()
        rule.onNodeWithTag("version-picker").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressDPadCenter(); rule.waitForIdle()
        rule.onNodeWithTag("version-latest").assertIsFocused()
        device.pressDPadDown(); device.pressDPadCenter(); rule.waitForIdle()
        rule.onNodeWithTag("version-menu").assertDoesNotExist()
        rule.onNodeWithTag("version-picker").assertTextContains("版本：v1 ▾")
        rule.onNodeWithTag("detail-primary-download").performClick()
        assertEquals("v1" to 1L, downloaded)
        rule.onNodeWithTag("version-picker").performClick()
        rule.onNodeWithTag("version-latest").performClick()
        rule.onNodeWithTag("detail-primary-download").performClick()
        assertEquals("v2" to 2L, downloaded)
    }
    @Test fun cachedVersionCannotStartDownloadWhileCheckingLatest() {
        show(busy = true)
        rule.onNodeWithTag("detail-primary-download").assertTextContains("正在检查…").performClick()
        assertNull(downloaded)
    }
    @Test fun selectingAnAlternativePackageChangesDownloadWithoutExpandingHistory() {
        val latest = app.releases.last()
        val assets = latest.assets + latest.assets.first().copy(id = 22, name = "alternative.apk")
        show(app.copy(releases = listOf(latest.copy(assets = assets))))
        rule.onNodeWithTag("detail-primary-download").assertTextContains("选择安装包").performClick()
        rule.onNodeWithTag("asset-option-22").performClick()
        rule.onNodeWithTag("detail-primary-download").performClick()
        assertEquals("v2" to 22L, downloaded)
        rule.onNodeWithTag("version-menu").assertDoesNotExist()
    }
}
