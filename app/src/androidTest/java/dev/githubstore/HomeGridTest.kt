package dev.githubstore

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.githubstore.domain.StoreApp
import dev.githubstore.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Larger catalog exists only in the test: exercise virtualized rows absent from the 5-app seed. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class HomeGridTest {
    @get:Rule val rule = createComposeRule()
    private val apps = (0..41).map {
        StoreApp("grid-$it", "应用 $it", "example/app", "example.app$it", "工具", "", "", "MIT", 0xFF168A7F, "A")
    }

    private fun showGrid() {
        rule.setContent {
            OrbitTheme {
                val scroll = rememberLazyGridState()
                val memory = remember { HomeFocusMemory() }
                val navigation = remember { Destination.entries.associateWith { FocusRequester() } }
                var detail by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxSize()) {
                    FloatingNavigation(Destination.Discover, 0, navigation, { true }, {})
                    if (detail) TvButton("返回网格", { detail = false })
                    else HomePage(apps, scroll, memory, navigation.getValue(Destination.Discover), true) { detail = true }
                }
            }
        }
        rule.onNodeWithTag("app-tile-grid-0").assertIsFocused()
    }

    @Test fun rapidRemoteMovesThroughSixColumnRowsWithoutLosingFocus() {
        showGrid()
        rule.mainClock.autoAdvance = false
        try {
            rule.onRoot().performKeyInput {
                repeat(5) { pressKey(Key.DirectionRight) }
                // No animation settlement between remote inputs.
                repeat(4) { pressKey(Key.DirectionDown) }
            }
        } finally { rule.mainClock.autoAdvance = true }
        rule.onNodeWithTag("app-tile-grid-29").assertIsFocused().assertIsDisplayed()
        val focused = rule.onNodeWithTag("app-tile-grid-29").fetchSemanticsNode().boundsInRoot
        val viewport = rule.onNodeWithTag("home-grid").fetchSemanticsNode().boundsInRoot
        assertTrue("焦点图标应完整处于滚动视口内", focused.top >= viewport.top && focused.bottom <= viewport.bottom)
    }

    @Test fun returningToGridRestoresOffscreenRowAndFocusedApplication() {
        showGrid()
        repeat(5) { rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        rule.onNodeWithTag("app-tile-grid-30").assertIsFocused().assertIsDisplayed()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("返回网格").performClick()
        rule.onNodeWithTag("app-tile-grid-30").assertIsFocused().assertIsDisplayed()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.onNodeWithTag("app-tile-grid-24").assertIsFocused().assertIsDisplayed()
    }
}
