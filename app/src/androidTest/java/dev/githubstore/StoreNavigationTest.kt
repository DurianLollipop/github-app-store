package dev.githubstore

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class StoreNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun opensRealAppDetailAndReturnsToDiscover() {
        rule.onNodeWithTag("app-tile-jellyfin-tv").assertHeightIsAtLeast(43.dp).performClick()
        rule.onNodeWithText("官方仓库").assertIsDisplayed()
        rule.onNodeWithText("下载 · 20.9 MB").assertIsDisplayed()
        rule.onNodeWithText("查看 README").assertIsDisplayed()
        rule.onNodeWithText("返回").performClick()
        rule.onNodeWithText("好应用，自由探索。").assertIsDisplayed()
    }

    @Test fun remoteCanEnterGridFromNavigation() {
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("官方仓库").assertIsDisplayed()
        val reader = rule.onNode(hasText("Jellyfin", substring = true) and isFocused() and !hasClickAction())
        var downPresses = 0
        while (!reader.isDisplayed() && downPresses++ < 4) {
            rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        }
        reader.assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.waitForIdle()
        rule.onNodeWithTag("app-tile-jellyfin-tv").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("官方仓库").assertIsDisplayed()
    }

    @Test fun remoteMovesAcrossRealAppsAndBackToNavigation() {
        rule.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        rule.onNodeWithTag("app-tile-jellyfin-tv").assertIsFocused()
        rule.onRoot().performKeyInput { repeat(4) { pressKey(Key.DirectionRight) } }
        rule.onNodeWithTag("app-tile-material-files").assertIsFocused().assertIsDisplayed()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithTag("app-tile-material-files").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.onNodeWithTag("nav-Discover").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("app-tile-material-files").assertIsFocused()
    }

    @Test fun searchFiltersCatalogAndHandlesNoResults() {
        rule.onNodeWithTag("nav-Search").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Jellyfin")
        rule.onAllNodes(hasText("Jellyfin") and hasClickAction() and !hasSetTextAction()).assertCountEquals(1)
        rule.onNode(hasSetTextAction()).performTextReplacement("不存在的应用-999")
        rule.onNodeWithText("没有找到应用").assertIsDisplayed()
    }

    @Test fun downloadsHasHonestEmptyState() {
        rule.onNodeWithTag("nav-Downloads").performClick()
        rule.onNodeWithText("下载中心").assertIsDisplayed()
        rule.onNodeWithText("好应用，准备就绪").assertIsDisplayed()
    }

    @Test fun pageTransitionsCanBeInterruptedByAnotherDestination() {
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag("nav-Downloads").performClick()
            rule.mainClock.advanceTimeBy(32)
            rule.onNodeWithTag("nav-Settings").performClick()
            rule.mainClock.advanceTimeBy(32)
            rule.onNodeWithTag("nav-Search").performClick()
        } finally { rule.mainClock.autoAdvance = true }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assertIsDisplayed()
        rule.onNodeWithText("下载中心").assertDoesNotExist()
        rule.onNodeWithText("简单，也尽在掌控").assertDoesNotExist()
    }

    @Test fun searchStateSurvivesOpeningAndReturningFromDetail() {
        rule.onNodeWithTag("nav-Search").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Jellyfin")
        rule.onNode(hasText("Jellyfin") and hasClickAction() and !hasSetTextAction()).performClick()
        rule.onNodeWithText("官方仓库").assertIsDisplayed()
        rule.onNodeWithText("返回").performClick()
        rule.onNode(hasSetTextAction()).assertTextContains("Jellyfin")
        rule.onAllNodes(hasText("Jellyfin") and hasClickAction() and !hasSetTextAction()).assertCountEquals(1)
    }

    @Test fun screenshotPreviewClosesAndRestoresRemoteFocus() {
        rule.onNodeWithTag("app-tile-jellyfin-tv").performClick()
        rule.onNodeWithTag("app-detail").performScrollToNode(hasContentDescription("Jellyfin 真实截图 1"))
        rule.onNodeWithContentDescription("Jellyfin 真实截图 1").performClick()
        rule.onNodeWithText("关闭预览").assertIsDisplayed().assertIsFocused().performClick()
        rule.onNodeWithText("关闭预览").assertDoesNotExist()
        rule.onNodeWithContentDescription("Jellyfin 真实截图 1").assertIsFocused()
    }
}
