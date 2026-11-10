package dev.githubstore.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.githubstore.GitHubSearchState
import dev.githubstore.domain.StoreApp
import kotlinx.coroutines.launch

@Composable internal fun GitHubSearchPage(state: GitHubSearchState, navigation: FocusRequester, input: FocusRequester,
    focus: MutableMap<String, FocusRequester>, onQuery: (String) -> Unit, onSubmit: () -> Unit,
    onMore: () -> Unit, onSelect: (StoreApp) -> Unit) {
    val submit = remember { FocusRequester() }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    fun enterResults(): Boolean {
        val first = state.results.firstOrNull()
        if (first == null) { submit.requestFocus(); return true }
        if (grid.layoutInfo.visibleItemsInfo.any { it.key == first.id }) focus[first.id]?.requestFocus()
        else scope.launch { grid.scrollToItem(0); withFrameNanos { }; focus[first.id]?.requestFocus() }
        return true
    }
    fun search() { keyboard?.hide(); onSubmit() }
    Column(Modifier.fillMaxSize().padding(34.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("搜索 GitHub", "搜索全部公开仓库，支持仓库名、关键词和 GitHub 搜索语法")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(state.query, onQuery, Modifier.weight(1f).focusRequester(input).testTag("github-search-input")
                .onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                        Key.DirectionDown -> enterResults()
                        Key.DirectionUp -> { navigation.requestFocus(); true }
                        else -> false
                    }
                }, singleLine = true, enabled = LocalPageActive.current,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("例如：kodi、topic:android-tv、owner/repo", fontSize = 13.sp) },
                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
            TvButton("搜索", ::search, Modifier.focusRequester(submit).testTag("github-search-submit").onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && state.results.isNotEmpty()) enterResults() else false
            }, primary = true)
        }
        when {
            state.query.isBlank() -> Text("输入关键词，在 GitHub 上发现更多项目。", color = Muted, fontSize = 14.sp)
            state.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp)
                Text("正在搜索 GitHub…", color = Muted, fontSize = 13.sp)
            }
            state.results.isEmpty() && state.error == null -> Text("GitHub 没有找到匹配的仓库，请换一个关键词。", color = Muted, fontSize = 14.sp)
            else -> Text("GitHub · 已加载 ${state.results.size} / ${state.total} 个仓库" + if (state.incomplete) " · 部分结果，建议细化关键词" else "",
                color = Muted, fontSize = 11.sp)
        }
        state.error?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(it, color = Error, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TvButton("重试", if (state.results.isEmpty()) onSubmit else onMore)
            }
        }
        if (state.results.isNotEmpty()) {
            LazyVerticalGrid(GridCells.Adaptive(190.dp), Modifier.weight(1f).testTag("github-search-results"), state = grid,
                horizontalArrangement = Arrangement.spacedBy(17.dp), verticalArrangement = Arrangement.spacedBy(17.dp), contentPadding = PaddingValues(7.dp)) {
                items(state.results, key = { it.id }) { app ->
                    AppCard(app, Modifier.fillMaxWidth().testTag("search-repo-${app.id}")
                        .focusRequester(focus.getOrPut(app.id) { FocusRequester() }).onPreviewKeyEvent {
                            val cell = grid.layoutInfo.visibleItemsInfo.firstOrNull { cell -> cell.key == app.id }
                            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp && cell?.row == 0) {
                                input.requestFocus(); true
                            } else false
                        }, { onSelect(app) }, showRepository = true)
                }
            }
            if (state.page >= 50 && state.total > 1000) Text("已达到 GitHub 单次搜索的 1000 条上限，请细化关键词。", color = Muted, fontSize = 11.sp)
            if (state.hasMore) TvButton(if (state.loadingMore) "正在加载…" else "加载更多仓库", onMore, Modifier.testTag("github-search-more"))
        }
    }
}
