package dev.githubstore

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.githubstore.data.*
import androidx.lifecycle.ViewModelProvider
import dev.githubstore.data.RepositoryPage
import dev.githubstore.domain.StoreApp
import kotlinx.coroutines.delay
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class StoreNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private var failSearch = false
    private lateinit var original: GitHubDiscovery
    private lateinit var originalDiscovery: DiscoveryRepository
    private val store get() = rule.activity.application as StoreApplication
    @Before fun fakeGitHub() {
        original = store.github
        originalDiscovery = store.discovery
        store.discovery = object : DiscoveryRepository(store, { store.github }, store.client) {
            override fun cached(mode: DiscoveryMode, sort: HotSort, period: HotPeriod) = DiscoverySnapshot(store.catalog.apps.value.filter { it.id != "orbit-store" }.map { app -> DiscoveryItem(if (app.releases.isNotEmpty()) app else app.copy(releases = listOf(dev.githubstore.domain.AppRelease(100, "fixture", "fixture", "", "", false, "", listOf(dev.githubstore.domain.ReleaseAsset(100, "fixture.apk", "https://github.com/fixture/app/releases/download/v1/fixture.apk", 100, null)))))) }, System.currentTimeMillis())
            override suspend fun load(mode: DiscoveryMode, sort: HotSort, period: HotPeriod, force: Boolean, onPartial: (DiscoverySnapshot) -> Unit) = cached(mode, sort, period)
        }
        store.github = object : GitHubDiscovery(store.client) {
            override suspend fun search(query: String, page: Int): RepositoryPage {
                delay(30)
                if (failSearch) throw GitHubRateLimitException(System.currentTimeMillis() + 60_000, "GitHub 额度已用完")
                val external = store.catalog.apps.value.first().copy(id = "github-99999", name = "OutsideCatalog",
                    repository = "author/OutsideCatalog", packageName = "", certificateSha256 = emptySet(), releases = emptyList())
                if (query == "many") return RepositoryPage((0..39).map { external.copy(id = "multi-$it", name = "Repo $it", repository = "fixture/repo$it") }, 40, false)
                if (query == "outside") return RepositoryPage(listOf(external), 1, false)
                if (query == "pages") return RepositoryPage(listOf(external.copy(id = "github-$page", name = "Repo$page")), 2, page == 1)
                val result = store.catalog.apps.value.filter { it.id != "orbit-store" && it.name.contains(query, true) }
                return RepositoryPage(result, result.size, false)
            }
            override suspend fun repository(name: String) = store.catalog.apps.value.firstOrNull { it.repository.equals(name, true) } ?: store.catalog.apps.value.first().copy(name = name.substringAfter('/'), repository = name, packageName = "", certificateSha256 = emptySet(), releases = emptyList())
            override suspend fun details(app: StoreApp) = app.copy(checkedAt = System.currentTimeMillis())
        }
        rule.runOnUiThread { ViewModelProvider(rule.activity)[StoreViewModel::class.java].refreshHome() }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("app-tile-jellyfin-tv").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun restoreGitHub() { store.github = original; store.discovery = originalDiscovery }
    private fun awaitResult(name: String) {
        rule.waitUntil(5000) { rule.onAllNodes(hasText(name) and hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun opensRealAppDetailAndReturnsToDiscover() {
        rule.onNodeWithTag("app-tile-jellyfin-tv").assertHeightIsAtLeast(43.dp).performClick()
        rule.onNodeWithText("官方仓库").assertIsDisplayed()
        rule.waitUntil(5000) { rule.onAllNodes(hasText("下载 · 20.9 MB")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("下载 · 20.9 MB").assertIsDisplayed()
        rule.onNodeWithTag("app-detail").performScrollToNode(hasText("查看 README"))
        rule.onNodeWithText("查看 README").assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.waitForIdle()
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
        val firstId = ViewModelProvider(rule.activity)[StoreViewModel::class.java].home.value.items.first().app.id
        val lastId = ViewModelProvider(rule.activity)[StoreViewModel::class.java].home.value.items.last().app.id
        rule.onNodeWithTag("app-tile-$firstId").assertIsFocused()
        rule.onRoot().performKeyInput { repeat(4) { pressKey(Key.DirectionRight) } }
        rule.onNodeWithTag("app-tile-$lastId").assertIsFocused().assertIsDisplayed()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithTag("app-tile-$lastId").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.onNodeWithTag("discover-mode-PERSONAL").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("app-tile-$lastId").assertIsFocused()
    }

    @Test fun rateLimitedRefreshRetainsSameQueryResults() {
        rule.onNodeWithTag("nav-Search").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("outside")
        awaitResult("OutsideCatalog")
        rule.runOnUiThread { failSearch = true; ViewModelProvider(rule.activity)[StoreViewModel::class.java].submitSearch() }
        rule.waitUntil(5000) { ViewModelProvider(rule.activity)[StoreViewModel::class.java].search.value.error?.contains("已保留此前结果") == true }
        rule.onNode(hasText("OutsideCatalog") and hasClickAction()).assertIsDisplayed()
        rule.onNodeWithTag("github-search-input").assertTextContains("outside")
    }

    @Test fun searchFiltersCatalogAndHandlesNoResults() {
        rule.onNodeWithTag("nav-Search").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Jellyfin")
        awaitResult("Jellyfin")
        rule.onAllNodes(hasText("Jellyfin") and hasClickAction() and !hasSetTextAction()).assertCountEquals(1)
        rule.onNode(hasSetTextAction()).performTextReplacement("不存在的应用-999")
        rule.waitUntil(5000) { rule.onAllNodes(hasText("GitHub 没有找到匹配的仓库，请换一个关键词。")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("GitHub 没有找到匹配的仓库，请换一个关键词。").assertIsDisplayed()
    }

    @Test fun categoryRemoteCanMoveDownToResultsAndOpenApp() {
        rule.onNodeWithTag("nav-Categories").performClick()
        rule.onNode(hasText("全部") and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNode(hasText("Jellyfin") and hasClickAction()).assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("官方仓库").assertIsDisplayed()
    }

    @Test fun remoteEntersSearchInputFromTopNavigation() {
        rule.onNodeWithTag("nav-Search").performClick().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("github-search-input").assertIsFocused()
    }

    @Test fun searchRemoteCanMoveDownFromInputToResult() {
        rule.onNodeWithTag("nav-Search").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("LocalSend")
        awaitResult("LocalSend")
        rule.onNode(hasSetTextAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNode(hasText("LocalSend") and hasClickAction() and !hasSetTextAction()).assertIsFocused()
    }

    @Test fun filteredCategorySupportsDownUpAndDetailReturn() {
        rule.onNodeWithTag("nav-Categories").performClick()
        val category = rule.onNodeWithTag("browse-category-效率工具")
        category.performClick().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("browse-app-localsend").assertIsFocused().assertIsDisplayed()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        category.assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("官方仓库").assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.waitForIdle()
        rule.onNodeWithTag("browse-app-localsend").assertIsFocused()
    }

    @Test fun emptySearchKeepsAnActionFocusedWhenPressingDown() {
        rule.onNodeWithTag("nav-Search").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("不存在的应用-999")
        rule.onNode(hasSetTextAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("github-search-submit").assertIsFocused()
    }

    @Test fun thirdPartyIconsAreNotBundledInApk() {
        org.junit.Assert.assertTrue(rule.activity.assets.list("icons").isNullOrEmpty())
    }

    @Test fun globalSearchOpensRepositoryOutsideCuratedCatalog() {
        rule.onNodeWithTag("nav-Search").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("outside")
        awaitResult("OutsideCatalog")
        rule.onNodeWithTag("search-repo-github-99999").performClick()
        rule.onNodeWithText("OutsideCatalog").assertIsDisplayed()
        rule.onNodeWithText("官方仓库").assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.waitForIdle()
        rule.onNodeWithTag("search-repo-github-99999").assertIsFocused()
    }

    @Test fun searchLoadsAnotherGithubPageWithoutReplacingExistingResults() {
        rule.onNodeWithTag("nav-Search").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("pages")
        awaitResult("Repo1")
        rule.onNodeWithTag("github-search-more").performClick()
        awaitResult("Repo2")
        rule.onAllNodes(hasText("Repo1") and hasClickAction()).assertCountEquals(1)
        rule.onAllNodes(hasText("Repo2") and hasClickAction()).assertCountEquals(1)
    }

    @Test fun returningFromDeepSearchResultResetsToTopWithoutClearingQueryOrResults() {
        rule.onNodeWithTag("nav-Search").performClick()
        rule.onNodeWithTag("github-search-input").performTextInput("many")
        awaitResult("Repo 0")
        rule.onNodeWithTag("github-search-results").performScrollToIndex(35)
        rule.onNodeWithTag("search-repo-multi-35").performClick()
        rule.onNodeWithText("官方仓库").assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.waitForIdle()
        rule.onNodeWithTag("github-search-input").assertTextContains("many")
        rule.onNodeWithTag("search-repo-multi-0").assertIsDisplayed().assertIsFocused()
        rule.onNodeWithText("GitHub · 已加载 40 / 40 个仓库").assertIsDisplayed()
    }

    @Test fun submittingRepositoryPathOpensDetailWithoutAnotherCardClick() {
        rule.onNodeWithTag("nav-Search").performClick()
        rule.onNodeWithTag("github-search-input").performTextInput("truefedex/tv-bro")
        rule.onNodeWithTag("github-search-input").performImeAction()
        rule.waitUntil(5000) { rule.onAllNodesWithText("官方仓库").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("TV Bro").assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.onNodeWithTag("github-search-input").assertTextContains("truefedex/tv-bro")
    }

    @Test fun decliningAgreementDoesNotEnqueueDownload() {
        rule.activity.getSharedPreferences("user-agreement", 0).edit().clear().commit()
        val before = store.tasks.tasks.value.size
        rule.onNodeWithTag("app-tile-jellyfin-tv").performClick()
        rule.waitUntil(5000) { rule.onAllNodes(hasText("下载 · 20.9 MB")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("detail-primary-download").performClick()
        rule.onNodeWithTag("agreement-decline").assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(before, store.tasks.tasks.value.size)
        org.junit.Assert.assertFalse(store.agreement.accepted)
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
        awaitResult("Jellyfin")
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
