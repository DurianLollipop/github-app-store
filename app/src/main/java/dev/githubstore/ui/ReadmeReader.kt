package dev.githubstore.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.githubstore.domain.StoreApp
import dev.githubstore.readme.*
import kotlinx.coroutines.*
import java.net.URLDecoder

internal class ReaderBackController { var back: (() -> Unit)? = null }

@Composable internal fun ReadmeReader(app: StoreApp, repository: ReadmeRepository, onClose: () -> Unit, onExternal: (String) -> Unit, backController: ReaderBackController? = null) {
    var file by remember(app.id) { mutableStateOf<ReadmeFile?>(null) }
    var blocks by remember { mutableStateOf<List<DocumentBlock>>(emptyList()) }
    var languages by remember { mutableStateOf<List<LanguageFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var languageMenu by remember { mutableStateOf(false) }
    var image by remember { mutableStateOf<String?>(null) }
    val stack = remember { mutableStateListOf<Pair<ReadmeFile, Int>>() }
    val collapsed = remember { mutableStateMapOf<Int, Boolean>() }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val focus = remember { mutableMapOf<Int, FocusRequester>() }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var languageJob by remember { mutableStateOf<Job?>(null) }
    var sequence by remember { mutableIntStateOf(0) }
    fun load(path: String?, rememberChoice: Boolean = false, force: Boolean = false, from: Pair<ReadmeFile, Int>? = null) {
        loadJob?.cancel(); languageJob?.cancel(); sequence++
        val token = sequence
        loadJob = scope.launch {
            loading = true; error = null
            try {
                val loaded = repository.load(app, path, force)
                val parsed = repository.blocks(app, loaded)
                ensureActive()
                if (from != null) stack += from
                file = loaded; blocks = parsed; collapsed.clear()
                scroll.requestScrollToItem(0)
                if (rememberChoice) repository.select(app, loaded)
                languageJob = scope.launch {
                    val choices = repository.languages(app, loaded)
                    if (token == sequence) languages = choices
                }
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) {
                error = e.message ?: "README 获取失败"
                if (path != null && e is dev.githubstore.data.GitHubApiException && e.status == 404) repository.forget(app)
                if (path != null && (file == null || (e is dev.githubstore.data.GitHubApiException && e.status == 404))) {
                    // Default document remains a deliberate fallback; never erase a choice on a transient error.
                    try { val fallback = repository.load(app); file = fallback; blocks = repository.blocks(app, fallback); scroll.requestScrollToItem(0) }
                    catch (c: CancellationException) { throw c }
                    catch (_: Exception) { }
                }
            } finally { if (token == sequence) loading = false }
        }
    }
    fun back() {
        if (image != null) { image = null; return }
        if (stack.isEmpty()) onClose() else {
            loadJob?.cancel(); languageJob?.cancel(); sequence++
            val previous = stack.removeAt(stack.lastIndex)
            scope.launch { file = previous.first; blocks = repository.blocks(app, previous.first); collapsed.clear(); scroll.scrollToItem(previous.second)
                withFrameNanos { }; runCatching { focus[previous.second]?.requestFocus() } }
        }
    }
    fun link(value: String) {
        if (value.startsWith('#')) {
            val anchor = URLDecoder.decode(value.drop(1), "UTF-8")
            val index = blocks.indexOfFirst { it.anchor == anchor }
            if (index >= 0) scope.launch { scroll.scrollToItem(index); withFrameNanos { }; runCatching { focus[index]?.requestFocus() } }
            else error = "没有找到此章节，可查看原文"
            return
        }
        val current = file ?: return
        val prefix = "https://github.com/${app.repository}/blob/${current.ref}/"
        if (value.startsWith(prefix) && value.substringBefore('#').endsWith(".md", true)) {
            load(URLDecoder.decode(value.removePrefix(prefix).substringBefore('#'), "UTF-8"), from = current to scroll.firstVisibleItemIndex)
        } else if (value.startsWith("https://")) onExternal(value)
    }
    val latestBack by rememberUpdatedState<() -> Unit>({ back() })
    DisposableEffect(backController) {
        backController?.back = { latestBack() }
        onDispose { backController?.back = null }
    }
    LaunchedEffect(app.id) { load(repository.preferred(app)); withFrameNanos { }; first.requestFocus() }
    DisposableEffect(Unit) { onDispose { loadJob?.cancel(); languageJob?.cancel() } }
    BackHandler { back() }
    Column(Modifier.fillMaxWidth().testTag("readme-reader"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton(if (stack.isEmpty()) "返回详情" else "返回上层文档", ::back, Modifier.focusRequester(first).testTag("readme-back"))
            if (languages.size > 1) Box {
                TvButton("README 语言：${languages.firstOrNull { it.path == file?.path }?.label ?: "选择"} ▾", { languageMenu = true }, Modifier.testTag("readme-language"))
                DropdownMenu(languageMenu, { languageMenu = false }, Modifier.heightIn(max = 200.dp)) {
                    languages.forEach { choice -> DropdownMenuItem(text = { Text("${choice.label} · ${choice.path}", fontSize = 12.sp) },
                        onClick = { languageMenu = false; if (choice.path != file?.path) load(choice.path, true) }, Modifier.testTag("readme-language-${choice.path}")) }
                }
            }
            TvButton("查看原文", { onExternal(file?.url ?: app.readmeUrl.ifBlank { app.sourceUrl }) })
            TvButton("重试", { load(file?.path ?: repository.preferred(app), force = true) })
        }
        Text("${file?.path ?: "README"}${if (loading) " · 正在加载…" else ""}${if (file?.cached == true) " · 缓存" else ""}", color = Ink, fontSize = 16.sp)
        file?.let { Text("获取于 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(it.fetchedAt))}", color = Muted, fontSize = 10.sp) }
        error?.let { Text(it, color = Error, fontSize = 12.sp) }
        if (file == null && !loading) Text("暂无可读 README，可重试或查看原文。", color = Muted)
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 300.dp).testTag("readme-content"), state = scroll,
            verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
            itemsIndexed(blocks) { index, block ->
                if (block.kind != "details" && block.group != 0 && collapsed[block.group] != false) return@itemsIndexed
                val requester = focus.getOrPut(index) { FocusRequester() }
                when (block.kind) {
                    "details" -> TvButton("${if (collapsed[block.group] == false) "▾" else "▸"} ${block.text}", { collapsed[block.group] = collapsed[block.group] != false }, Modifier.focusRequester(requester))
                    "image" -> FocusSurface({ image = block.url }, Modifier.focusRequester(requester).fillMaxWidth()) {
                        AsyncImage(block.url, block.text.ifBlank { "README 图片" }, Modifier.fillMaxWidth().height(140.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                    }
                    "rule" -> HorizontalDivider(color = Control)
                    else -> {
                        val horizontal = rememberScrollState()
                        val wide = block.kind in setOf("code", "table")
                        val text = remember(block) { buildAnnotatedString { block.runs.forEach { run ->
                            withStyle(SpanStyle(fontWeight = if (run.bold) FontWeight.Bold else null, fontStyle = if (run.italic) FontStyle.Italic else null,
                                fontFamily = if (run.code) FontFamily.Monospace else null, color = if (run.href.isNotEmpty()) Accent else Ink)) { append(run.text) }
                        } } }
                        FocusSurface({}, Modifier.fillMaxWidth().focusRequester(requester).testTag("readme-block-$index")
                            .onPreviewKeyEvent { event ->
                                if (wide && event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionLeft, Key.DirectionRight)) {
                                    scope.launch { horizontal.animateScrollTo((horizontal.value + if (event.key == Key.DirectionRight) 250 else -250).coerceIn(0, horizontal.maxValue)) }; true
                                } else false
                            }) {
                            Text(text, fontSize = if (block.kind == "heading") (25 - block.level * 2).coerceAtLeast(15).sp else 13.sp, lineHeight = 23.sp,
                                modifier = Modifier.padding(10.dp).then(if (wide) Modifier.horizontalScroll(horizontal) else Modifier),
                                fontWeight = if (block.kind == "heading") FontWeight.SemiBold else FontWeight.Normal)
                        }
                        block.runs.filter { it.href.isNotEmpty() }.distinctBy { it.href }.forEach { run ->
                            TvButton("↗ ${run.text.take(70)}", { link(run.href) })
                        }
                    }
                }
            }
        }
    }
    OrbitDialog(image != null, { image = null }, width = 850) {
        AsyncImage(image, "README 图片预览", Modifier.fillMaxWidth().height(280.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        TvButton("关闭预览", { image = null })
    }
}
