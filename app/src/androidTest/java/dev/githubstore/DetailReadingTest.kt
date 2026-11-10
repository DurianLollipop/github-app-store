package dev.githubstore

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.githubstore.domain.StoreApp
import dev.githubstore.ui.AppDetail
import dev.githubstore.ui.OrbitTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class DetailReadingTest {
    @get:Rule val rule = createComposeRule()

    private val paragraphs = (1..30).map { index ->
        "介绍段落${index.toString().padStart(2, '0')}：" +
            "这是一段需要在电视上完整阅读的应用介绍，介绍应用用途、使用方法与功能。".repeat(4)
    }
    private val app = StoreApp(id = "reading-test", name = "阅读测试应用", repository = "example/app",
        packageName = "dev.example.reader", category = "工具", tagline = "用方向键浏览应用介绍",
        description = paragraphs.joinToString("\n\n"), license = "MIT", color = 0xFF168A7F, symbol = "R")

    private fun showDetail(detailApp: StoreApp = app, largeText: Boolean = false) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 1.8f else density.fontScale)) {
            OrbitTheme { Box(Modifier.fillMaxSize()) {
                AppDetail(detailApp, null, false, {}, {}, { _, _ -> }, {}, {}, {})
            } }
            }
        }
        rule.onNodeWithText("获取发布版本").assertIsFocused()
    }

    // Inspect the marker's actual character position, not merely a giant Text node
    // intersecting the viewport while its middle/end remain offscreen.
    private fun markerVisible(marker: String): Boolean {
        val query = rule.onAllNodes(hasText(marker, substring = true))
        val node = query.fetchSemanticsNodes().singleOrNull() ?: return false
        val layouts = mutableListOf<TextLayoutResult>()
        query.onFirst().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.singleOrNull() ?: return false
        val index = layout.layoutInput.text.text.indexOf(marker)
        if (index < 0) return false
        val character = layout.getBoundingBox(index)
        val viewport = rule.onNodeWithTag("app-detail").fetchSemanticsNode().boundsInRoot
        val top = node.positionInRoot.y + character.top
        val bottom = node.positionInRoot.y + character.bottom
        return top >= viewport.top && bottom <= viewport.bottom
    }

    private fun readByRemote(markers: Set<String>) {
        val seen = mutableSetOf<String>()
        var steps = 0
        while (!seen.containsAll(markers) && steps++ < 50) {
            markers.filterTo(seen, ::markerVisible)
            if (!seen.containsAll(markers)) rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        }
        assertTrue("方向键跳过了介绍内容，未能完整浏览：${markers - seen}", seen.containsAll(markers))
        repeat(38) { rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) } }
        rule.onNodeWithText("返回").assertIsDisplayed()
    }

    @Test fun remoteCanReadStartMiddleAndEndOfLongIntroduction() {
        showDetail()
        readByRemote(setOf("介绍段落01", "介绍段落15", "介绍段落30"))
    }

    @Test fun remoteCanReadOneLongParagraphAtLargeFontSize() {
        val description = "单段开头标记。" + "没有换行的详细介绍内容。".repeat(70) +
            "单段中间标记。" + "继续介绍应用功能与使用方法。".repeat(70) + "单段结尾标记。"
        showDetail(app.copy(description = description), largeText = true)
        readByRemote(setOf("单段开头标记", "单段中间标记", "单段结尾标记"))
    }

    @Test fun touchCanStillScrollThroughIntroduction() {
        showDetail()
        val list = rule.onNodeWithTag("app-detail")
        val distance = list.fetchSemanticsNode().size.height * .18f
        var steps = 0
        while (!markerVisible("介绍段落15") && steps++ < 16) {
            list.performTouchInput {
                swipe(center + Offset(0f, distance), center - Offset(0f, distance), durationMillis = 600)
            }
        }
        assertTrue("触摸滑动未能显示介绍中段", markerVisible("介绍段落15"))
    }
}
