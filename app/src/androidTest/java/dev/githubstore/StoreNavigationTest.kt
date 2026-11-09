package dev.githubstore

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class StoreNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun opensRealAppDetailAndReturnsToDiscover() {
        rule.onNodeWithText("探索 Jellyfin").performClick()
        rule.onNodeWithText("官方仓库").assertIsDisplayed()
        rule.onNodeWithText("下载 · 20.9 MB").assertIsDisplayed()
        rule.onNodeWithText("返回").performClick()
        rule.onNodeWithText("好应用，\n自由探索。").assertIsDisplayed()
    }

    @Test fun remoteCanEnterContentFromRail() {
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("官方仓库").assertIsDisplayed()
    }

    @Test fun searchFiltersCatalogAndHandlesNoResults() {
        rule.onNodeWithText("搜索").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Jellyfin")
        rule.onAllNodes(hasText("Jellyfin") and hasClickAction() and !hasSetTextAction()).assertCountEquals(1)
        rule.onNode(hasSetTextAction()).performTextReplacement("不存在的应用-999")
        rule.onNodeWithText("没有找到应用").assertIsDisplayed()
    }

    @Test fun downloadsHasHonestEmptyState() {
        rule.onNodeWithText("下载").performClick()
        rule.onNodeWithText("下载中心").assertIsDisplayed()
        rule.onNodeWithText("好应用，准备就绪").assertIsDisplayed()
    }
}
