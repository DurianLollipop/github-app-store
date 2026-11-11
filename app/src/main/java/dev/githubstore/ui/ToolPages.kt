package dev.githubstore.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.githubstore.data.*
import dev.githubstore.domain.*
import kotlinx.coroutines.launch

@Composable internal fun AgreementPanel(readOnly: Boolean, onAccept: () -> Unit, onDecline: () -> Unit, assetName: String = "user-agreement.txt", title: String = "用户协议与第三方软件风险") {
    val context = LocalContext.current
    val paragraphs = remember { readingParagraphs(context.assets.open(assetName).bufferedReader().use { it.readText() }) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; first.requestFocus() }
    Text(title, color = Ink, fontSize = 22.sp)
    if (assetName == "user-agreement.txt") Text("GitHub 项目未经逐一安全审计，请自行判断是否安装。技术检查不代表安全认证。", color = Error, fontSize = 13.sp)
    LazyColumn(Modifier.fillMaxWidth().height(220.dp).testTag("agreement-text"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(paragraphs) { index, text -> FocusSurface({}, Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier)) {
            Text(text, color = Ink, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(10.dp))
        } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TvButton(if (readOnly) "关闭" else "不同意，取消操作", onDecline, Modifier.testTag("agreement-decline"))
        if (!readOnly) TvButton("我已阅读并同意", onAccept, Modifier.testTag("agreement-accept"), primary = true)
    }
}

@Composable internal fun DiscoveryPage(snapshot: DiscoverySnapshot, loading: Boolean, mode: DiscoveryMode, sort: HotSort, period: HotPeriod,
    scroll: LazyGridState, memory: HomeFocusMemory, navigation: FocusRequester, returnToken: Int,
    onMode: (DiscoveryMode, HotSort, HotPeriod) -> Unit, onRefresh: () -> Unit, onExternal: (String) -> Unit,
    onVisible: (List<String>) -> Unit = {}, onSelect: (StoreApp) -> Unit) {
    val toolbar = remember { FocusRequester() }
    val filterFocus = remember { FocusRequester() }
    val firstFilter = remember { FocusRequester() }
    var filtersOpen by remember { mutableStateOf(false) }
    var restoreFilter by remember { mutableStateOf(false) }
    val active = LocalPageActive.current
    val focusScope = rememberCoroutineScope()
    val visibleCallback by rememberUpdatedState(onVisible)
    LaunchedEffect(scroll, mode, sort, period, active, loading) {
        if (active && !loading) snapshotFlow { scroll.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
            .distinctUntilChanged().collect { visibleCallback(it) }
    }
    LaunchedEffect(returnToken, active) {
        if (active && returnToken > 0) {
            scroll.scrollToItem(0); memory.lastId = snapshot.items.firstOrNull()?.app?.id
            withFrameNanos { }
            if (snapshot.items.isNotEmpty()) focusHomeApp(snapshot.items.map { it.app }, scroll, memory) else runCatching { toolbar.requestFocus() }
        }
    }
    fun closeFilters() { filtersOpen = false; restoreFilter = true }
    OrbitDialog(filtersOpen, ::closeFilters, width = 580, onClosed = {
        if (restoreFilter) { runCatching { filterFocus.requestFocus() }; restoreFilter = false }
    }) {
        Text("热门筛选", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Text("排序方式", color = Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HotSort.entries.forEachIndexed { index, item -> FocusSurface({ onMode(mode, item, period) },
                Modifier.testTag("hot-sort-${item.name}").then(if (index == 0) Modifier.focusRequester(firstFilter) else Modifier), selected = item == sort, radius = 50) {
                Text(item.label, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 13.sp)
            } }
        }
        Text("增长统计期间", color = Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HotPeriod.entries.forEach { item -> FocusSurface({ onMode(mode, sort, item) }, Modifier.testTag("hot-period-${item.name}"), selected = item == period, radius = 50) {
                Text(item.label, Modifier.padding(horizontal = 18.dp, vertical = 10.dp), fontSize = 13.sp)
            } }
        }
        Text("期间只影响新增 Star；总 Star 按累计数量排序。", color = Muted, fontSize = 11.sp)
        snapshot.items.firstOrNull()?.let { Text("来源：${it.source} ${it.window}", color = Muted, fontSize = 11.sp) }
        snapshot.error?.let { Text(it, color = Muted, fontSize = 11.sp, maxLines = 3) }
        TvButton("完成", ::closeFilters, Modifier.testTag("hot-filter-done"))
        LaunchedEffect(Unit) { withFrameNanos { }; firstFilter.requestFocus() }
    }
    Column(Modifier.fillMaxSize()) {
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 38.dp, vertical = 2.dp).testTag("discovery-toolbar").onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false
            else if (it.key == Key.DirectionUp) { navigation.requestFocus(); true }
            else if (it.key == Key.DirectionDown && snapshot.items.isNotEmpty()) {
                if (!focusVisibleHomeApp(snapshot.items.map { item -> item.app }, scroll, memory)) focusScope.launch { focusHomeApp(snapshot.items.map { item -> item.app }, scroll, memory) }
                true
            } else false
        }, horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(DiscoveryMode.entries) { index, item -> FocusSurface({ onMode(item, sort, period) },
                Modifier.testTag("discover-mode-${item.name}").then(if (index == 0) Modifier.focusRequester(toolbar) else Modifier), selected = item == mode, radius = 50) {
                Text(item.label, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            } }
            if (mode in setOf(DiscoveryMode.PLATFORM, DiscoveryMode.GLOBAL)) item {
                FocusSurface({ filtersOpen = true }, Modifier.focusRequester(filterFocus).testTag("hot-filter"), radius = 50) {
                    Text("${if (sort == HotSort.GROWTH) "${period.label}新增 Star" else "总 Star"} ▾", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 11.sp)
                }
            }
            item { FocusSurface(onRefresh, Modifier.testTag("discovery-refresh"), radius = 50) {
                Text(if (loading) "获取中…" else "刷新", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 11.sp)
            } }
        }
        Row(Modifier.fillMaxWidth().padding(start = 42.dp, end = 44.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (mode == DiscoveryMode.GLOBAL) "GitHub 热门项目" else if (mode == DiscoveryMode.PERSONAL) "好应用，自由探索。" else mode.label,
                color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("${snapshot.items.size} 个项目" + if (snapshot.fetchedAt > 0) " · 更新于 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(snapshot.fetchedAt))}" else "",
                color = Muted, fontSize = 10.sp)
        }
        // Keep status to one line; semantics retain the full text for accessibility.
        val status = snapshot.error ?: snapshot.items.firstOrNull()?.let { "${it.source} ${it.window}" }.orEmpty()
        if (status.isNotBlank()) Text(status, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 44.dp).testTag("discovery-status"))
        if (snapshot.items.isEmpty()) {
            Column(Modifier.padding(44.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (loading) "正在发现项目…" else "暂无可用结果", fontSize = 24.sp, color = Ink)
                Text("可以使用顶部全站搜索，或输入 owner/repo 添加仓库。", color = Muted, fontSize = 13.sp)
                TvButton("查看 GitHub 趋势", { onExternal("https://github.com/trending?since=${period.query}") })
            }
        } else Box(Modifier.weight(1f)) {
            HomePage(snapshot.items.map { it.app }, scroll, memory, toolbar, restoreFocus = false, showHeading = false, onSelect = onSelect,
                badges = snapshot.items.associate { item -> item.app.id to when {
                    mode in setOf(DiscoveryMode.GLOBAL, DiscoveryMode.PLATFORM) && sort == HotSort.GROWTH -> "${period.label} +${item.growth ?: "未知"} ★ · 总 ${item.app.stars}"
                    mode in setOf(DiscoveryMode.GLOBAL, DiscoveryMode.PLATFORM) -> "★ ${item.app.stars}"
                    else -> item.reason
                } })
        }
    }
}

@Composable internal fun UserLibraryPage(records: List<UserRepository>, installed: Map<String, InstalledApp>, busy: Boolean,
    onRefresh: () -> Unit, onSelect: (StoreApp) -> Unit, onOpen: (StoreApp) -> Unit,
    onFavorite: (StoreApp) -> Unit, onSubscribe: (StoreApp) -> Unit, onChecks: (StoreApp, String) -> Unit,
    onPrerelease: (StoreApp) -> Unit, onUnlink: (StoreApp, String) -> Unit, onHide: (StoreApp) -> Unit, onBindingChannel: (StoreApp, String) -> Unit, returnToken: Int, ignored: Set<String>, onIgnore: (StoreApp, ReleaseAsset) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("已安装") }
    val state = remember(returnToken) { LazyListState() }
    val first = remember { FocusRequester() }
    val active = LocalPageActive.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(returnToken, active) { if (active && returnToken > 0) { state.scrollToItem(0); withFrameNanos { }; runCatching { first.requestFocus() } } }
    val selected = records.filter { when (tab) { "收藏" -> it.favorite; "订阅" -> it.subscribed; else -> it.bindings.any { b -> b.identity.packageName in installed } || (it.bindings.isEmpty() && it.app.packageName in installed) } }
    LazyColumn(Modifier.testTag("user-library"), state = state, contentPadding = PaddingValues(34.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SectionTitle("我的项目", "收藏便于访问；订阅与已安装关联分别检查新发布")
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("已安装", "收藏", "订阅").forEachIndexed { index, label -> TvButton(label, { tab = label; scope.launch { state.scrollToItem(0) } },
                    Modifier.then(if (index == 0) Modifier.focusRequester(first) else Modifier).testTag("library-tab-$label"), primary = tab == label) }
                TvButton(if (busy) "检查中…" else "检查更新", onRefresh)
            }
        }
        if (selected.isEmpty()) item { Text("暂无${tab}项目，可在详情中收藏、订阅或关联安装。", color = Muted, modifier = Modifier.padding(24.dp)) }
        items(selected, key = { it.app.id }) { record ->
            val app = record.app
            Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(app.name, color = Ink, fontSize = 20.sp)
                Text(app.repository, color = Muted, fontSize = 12.sp)
                if (app.checkedAt > 0) Text("上次成功检查：${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(app.checkedAt))}", color = Muted, fontSize = 10.sp)
                if (record.bindings.isEmpty() && app.packageName in installed) Text("旧版记录：已检测到安装，来源关联待重新检查；不会自动检查更新。", color = Muted, fontSize = 11.sp)
                record.checkError?.let { Text("检查失败：$it；已保留已有资料", color = Error, fontSize = 11.sp) }
                val latest = selectedRelease(app.releases.filter { record.prerelease || !it.prerelease }, null)
                val latestAsset = latest?.let { AssetPolicy.recommended(it, android.os.Build.SUPPORTED_ABIS.toList(), android.os.Build.VERSION.SDK_INT) }
                val ignoredVersion = latestAsset?.let { "${app.id}:${it.versionCode ?: it.id}" in ignored } == true
                val installedTags = record.bindings.filter { it.identity.packageName in installed }.map { it.installedTag }
                Text(when {
                    ignoredVersion -> "已忽略此版本 ${latest?.tag}"
                    latest == null -> "尚无发布记录"
                    installedTags.isNotEmpty() && latest.tag !in installedTags -> "新发布 ${latest.tag} · 下载后核对包名、签名与内部版本"
                    record.subscribed -> "当前发布 ${latest.tag}"
                    else -> "未发现新的发布记录"
                }, color = Accent, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { TvButton("版本与详情", { onSelect(app) }) }
                    item { TvButton(if (record.favorite) "取消收藏" else "收藏", { onFavorite(app) }) }
                    item { TvButton(if (record.subscribed) "取消订阅" else "订阅", { onSubscribe(app) }) }
                    item { TvButton(if (record.prerelease) "渠道：含预发布" else "渠道：稳定版", { onPrerelease(app) }) }
                    if (latestAsset != null) item { TvButton(if (ignoredVersion) "恢复版本提醒" else "忽略此版本", { onIgnore(app, latestAsset) }) }
                    item { TvButton("隐藏推荐", { onHide(app) }) }
                }
                record.bindings.forEach { binding ->
                    Text("${binding.identity.packageName} · ${installed[binding.identity.packageName]?.versionName ?: "未检测到安装"}", color = Muted, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { TvButton("打开", { onOpen(app.copy(packageName = binding.identity.packageName)) }) }
                        item { TvButton(if (binding.checks) "关闭此包更新检查" else "开启此包更新检查", { onChecks(app, binding.identity.packageName) }) }
                        item { TvButton(if (binding.prerelease) "此包：含预发布" else "此包：稳定版", { onBindingChannel(app, binding.identity.packageName) }) }
                        item { TvButton("解除此包关联", { onUnlink(app, binding.identity.packageName) }) }
                    }
                }
            }
            HorizontalDivider(color = Control)
        }
        item { Text("识别受系统包可见性限制。外部安装请在对应仓库详情中主动关联；不扫描全部设备应用。", color = Muted, fontSize = 10.sp) }
    }
}
