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
import dev.githubstore.data.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
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

    @Test fun compactDiscoveryShowsTwoRowsAndFilterRestoresRemoteFocus() {
        rule.setContent {
            OrbitTheme {
                val scroll = rememberLazyGridState()
                val memory = remember { HomeFocusMemory() }
                val navigation = remember { Destination.entries.associateWith { FocusRequester() } }
                var sort by remember { mutableStateOf(HotSort.GROWTH) }
                var period by remember { mutableStateOf(HotPeriod.WEEK) }
                SilkSceneHost(Modifier.fillMaxSize(), animate = false) {
                    Column(Modifier.fillMaxSize()) {
                        FloatingNavigation(Destination.Discover, 0, navigation, { true }, {})
                        DiscoveryPage(DiscoverySnapshot(apps.map { DiscoveryItem(it, 100, source = "测试夹具", window = "本周") }, 1),
                            false, DiscoveryMode.GLOBAL, sort, period, scroll, memory, navigation.getValue(Destination.Discover), 0,
                            onMode = { _, s, p -> sort = s; period = p }, onRefresh = {}, onExternal = {}, onSelect = {})
                    }
                }
            }
        }
        val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val grid = rule.onNodeWithTag("home-grid").fetchSemanticsNode().boundsInRoot
        assertTrue("网格应从屏幕前40%以内开始", grid.top < screen.height * .4f)
        val secondRow = rule.onNodeWithTag("app-tile-grid-11").fetchSemanticsNode().boundsInRoot
        assertTrue("首屏应完整容纳两行六列项目", secondRow.bottom <= grid.bottom)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(context.getExternalFilesDir(null), "compact-home-fixture.png"))
        rule.onNodeWithTag("hot-filter").performClick()
        rule.onNodeWithTag("hot-sort-GROWTH").assertIsFocused()
        rule.onNodeWithTag("hot-period-MONTH").performClick()
        rule.onNodeWithTag("hot-sort-TOTAL").performClick()
        rule.onNodeWithTag("hot-filter-done").performClick()
        rule.onNodeWithTag("hot-filter").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("app-tile-grid-0").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("app-tile-grid-6").assertIsFocused().assertIsDisplayed()
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
                    if (detail) TvButton("返回网格", { memory.lastId = apps.first().id; detail = false })
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

    @Test fun returningToGridResetsToFirstVisibleApplication() {
        showGrid()
        repeat(5) { rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        rule.onNodeWithTag("app-tile-grid-30").assertIsFocused().assertIsDisplayed()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("返回网格").performClick()
        rule.onNodeWithTag("app-tile-grid-0").assertIsFocused().assertIsDisplayed()
    }
}
