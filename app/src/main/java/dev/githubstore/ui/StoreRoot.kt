package dev.githubstore.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.githubstore.BuildConfig
import dev.githubstore.StoreViewModel
import dev.githubstore.domain.*
import dev.githubstore.data.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class Destination(val title: String, val icon: ImageVector) {
    Discover("首页", Icons.Outlined.Home), Search("搜索", Icons.Outlined.Search),
    Categories("分类", Icons.Outlined.GridView), Downloads("下载", Icons.Outlined.FileDownload),
    Library("已安装", Icons.Outlined.Apps), Settings("设置", Icons.Outlined.Settings)
}

@Composable fun StoreRoot(model: StoreViewModel, onInstall: (DownloadTask) -> Unit,
                         onDownloadPermission: () -> Unit, openDownloads: Boolean,
                         onExternal: (String) -> Unit, onUninstall: (String) -> Unit) {
    val recommendedApps by model.apps.collectAsStateWithLifecycle()
    val home by model.home.collectAsStateWithLifecycle()
    val homeBusy by model.homeBusy.collectAsStateWithLifecycle()
    val homeSettings by model.discoverySettings.collectAsStateWithLifecycle()
    val records by model.library.collectAsStateWithLifecycle()
    var agreementOpen by remember { mutableStateOf(false) }
    var noticesOpen by remember { mutableStateOf(false) }
    var agreementReadOnly by remember { mutableStateOf(false) }
    var pendingDownload by remember { mutableStateOf<Triple<StoreApp, AppRelease, ReleaseAsset>?>(null) }
    var sourceTask by remember { mutableStateOf<DownloadTask?>(null) }
    var backToTop by rememberSaveable { mutableIntStateOf(0) }
    val appsInHome = home.items.map { item ->
        val known = records.firstOrNull { it.app.id == item.app.id }?.app
        if (known != null && known.iconUrl.isNotBlank() && known.iconUrl != item.app.iconUrl) item.app.copy(iconUrl = known.iconUrl) else item.app
    }.filter { app -> records.none { it.app.id == app.id && it.hidden } }
    fun requestInstall(task: DownloadTask) {
        if (!model.agreement.accepted) { agreementReadOnly = false; agreementOpen = true }
        else if (!task.sourceConfirmed || task.inspected == null) sourceTask = task else onInstall(task)
    }
    val discoveredApps by model.discovered.collectAsStateWithLifecycle()
    val searchState by model.search.collectAsStateWithLifecycle()
    val catalogApps = (recommendedApps + discoveredApps + appsInHome).distinctBy { it.id }
    val apps = appsInHome
    val categoryApps = (if (homeSettings.first == DiscoveryMode.GLOBAL) records.map { it.app } else appsInHome).filter { DiscoveryRules.eligible(it) }
    val tasks by model.tasks.collectAsStateWithLifecycle()
    val installed by model.installed.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val ignored by model.ignored.collectAsStateWithLifecycle()
    val reduced by model.reducedMotion.collectAsStateWithLifecycle()
    val automaticChecks by model.automaticChecks.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(if (openDownloads) Destination.Downloads else Destination.Discover) }
    var selectedApp by rememberSaveable { mutableStateOf<String?>(null) }
    var lastAppId by rememberSaveable { mutableStateOf<String?>(null) }
    val cardFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun select(app: StoreApp) { model.pauseDiscovery(); model.prepareDetail(app); lastAppId = app.id; selectedApp = app.id }
    LaunchedEffect(searchState.openRepository?.id) {
        searchState.openRepository?.let { app -> if (destination == Destination.Search) select(app); model.consumeRepositoryOpen() }
    }
    val homeScroll = rememberLazyGridState()
    val homeFocus = remember { HomeFocusMemory() }
    val navigationFocus = remember { Destination.entries.associateWith { FocusRequester() } }
    val browseEntry = remember { mapOf(Destination.Search to FocusRequester(), Destination.Categories to FocusRequester()) }
    val scope = rememberCoroutineScope()
    val pageState = rememberSaveableStateHolder()
    var lastMessage by remember { mutableStateOf("") }
    if (message != null) SideEffect { lastMessage = message!! }
    LaunchedEffect(Unit) { withFrameNanos { }; navigationFocus.getValue(destination).requestFocus() }
    fun backFromDetail() {
        homeFocus.lastId = apps.firstOrNull()?.id
        scope.launch {
            homeScroll.scrollToItem(0)
            backToTop++
            selectedApp = null
        }
    }
    BackHandler(selectedApp != null || destination != Destination.Discover) {
        if (selectedApp != null) backFromDetail() else {
            destination = Destination.Discover
            navigationFocus.getValue(Destination.Discover).requestFocus()
        }
    }
    OrbitTheme(forceReduced = reduced) {
        SilkSceneHost(Modifier.fillMaxSize(), animate = destination == Destination.Discover && selectedApp == null) {
            Column(Modifier.fillMaxSize()) {
                FloatingNavigation(destination, tasks.count { it.active }, navigationFocus,
                    onContentDown = {
                        if (selectedApp != null) false else when (destination) {
                            Destination.Discover -> {
                                if (!focusVisibleHomeApp(apps, homeScroll, homeFocus))
                                    scope.launch { focusHomeApp(apps, homeScroll, homeFocus) }
                                true
                            }
                            Destination.Search, Destination.Categories -> {
                                val target = destination
                                val requester = browseEntry.getValue(target)
                                if (!runCatching { requester.requestFocus() }.getOrDefault(false)) scope.launch {
                                    withFrameNanos { }
                                    if (destination == target && selectedApp == null) runCatching { requester.requestFocus() }
                                }
                                true
                            }
                            else -> false
                        }
                    },
                    onSelect = { model.pauseDiscovery(); destination = it; selectedApp = null })
                Box(Modifier.weight(1f).fillMaxWidth().revealOnLaunch()) {
                    PageTransition("${destination.name}:${selectedApp.orEmpty()}", selectedApp != null) { route ->
                        pageState.SaveableStateProvider(route) {
                            val shownDestination = Destination.valueOf(route.substringBefore(':'))
                            val app = catalogApps.firstOrNull { it.id == route.substringAfter(':') }
                            if (app != null) AppDetail(app, installed[app.packageName], busy.contains(app.id),
                                onBack = ::backFromDetail, onRefresh = { model.prepareDetail(app, true) },
                                onDownload = { release, asset ->
                                    if (!model.agreement.accepted) { pendingDownload = Triple(app, release, asset); agreementReadOnly = false; agreementOpen = true }
                                    else { onDownloadPermission(); model.download(app, release, asset); selectedApp = null; destination = Destination.Downloads }
                                },
                                onOpen = { model.open(app) }, onExternal = onExternal,
                                onUninstall = { onUninstall(app.packageName) },
                                readmes = model.readmes, record = records.firstOrNull { it.app.id == app.id },
                                onFavorite = { model.favorite(app) }, onSubscribe = { model.subscribe(app) }, onAssociate = { model.associate(app) }, onHistory = { model.loadHistory(app) })
                            else when (shownDestination) {
                                Destination.Discover -> DiscoveryPage(home.copy(items = home.items.filter { item -> apps.any { it.id == item.app.id } }.map { item -> item.copy(app = apps.first { it.id == item.app.id }) }), homeBusy,
                                    homeSettings.first, homeSettings.second, homeSettings.third, homeScroll, homeFocus, navigationFocus.getValue(Destination.Discover), backToTop,
                                    model::homeMode, { model.refreshHome(true) }, onExternal, model::enrichVisibleHome, ::select)
                                Destination.Search -> GitHubSearchPage(searchState.copy(results = searchState.results.map { found -> catalogApps.firstOrNull { it.id == found.id } ?: found }), navigationFocus.getValue(Destination.Search), browseEntry.getValue(Destination.Search), cardFocus, model::searchQuery, model::submitSearch, model::moreSearchResults, ::select, backToTop)
                                Destination.Categories -> BrowsePage(categoryApps, false, cardFocus, navigationFocus.getValue(Destination.Categories), browseEntry.getValue(Destination.Categories), ::select, backToTop)
                                Destination.Downloads -> DownloadsPage(tasks, catalogApps, ::requestInstall, model::cancel, model::retry, { selectedApp = it })
                                Destination.Library -> UserLibraryPage(records, installed, busy.isNotEmpty(), model::refreshAll, ::select, model::open,
                                    model::favorite, model::subscribe, model::installChecks, model::prerelease, model::unlink, model::hide, model::bindingChannel, backToTop, ignored, model::ignore)
                                Destination.Settings -> SettingsPage(model, catalogApps, model::refreshAll, model::clearCache, onExternal,
                                    onAgreement = { agreementReadOnly = true; agreementOpen = true }, onNotices = { noticesOpen = true }, onReset = model::resetPreferences,
                                    reduced = reduced, automaticChecks = automaticChecks, onMotion = model::toggleMotion, onChecks = model::toggleChecks, onClearData = model::clearUserData,
                                    onSelfUpdate = { selectedApp = "orbit-store"; model.refresh("orbit-store", true) })
                            }
                        }
                    }
                }
            }
        }
        OrbitDialog(agreementOpen, { agreementOpen = false; pendingDownload = null }, width = 850) {
            AgreementPanel(agreementReadOnly, onAccept = {
                model.agreement.accept(); agreementOpen = false
                pendingDownload?.let { (app, release, asset) -> onDownloadPermission(); model.download(app, release, asset); selectedApp = null; destination = Destination.Downloads }
                pendingDownload = null
            }, onDecline = { agreementOpen = false; pendingDownload = null })
        }
        OrbitDialog(noticesOpen, { noticesOpen = false }, width = 850) {
            AgreementPanel(true, {}, { noticesOpen = false }, "third-party-notices.txt", "阅读组件开源许可")
        }
        OrbitDialog(sourceTask != null, { sourceTask = null }, width = 750) {
            val task = sourceTask
            Text("确认安装包来源", color = Ink, fontSize = 22.sp)
            if (task != null) {
                Text("仓库：${task.repository}\n版本：${task.releaseTag}\n包名：${task.inspected?.packageName ?: "待检查"}\n内部版本：${task.inspected?.versionCode ?: "未知"}", color = Ink, fontSize = 13.sp)
                Text("签名 SHA-256：${task.inspected?.certificates?.joinToString() ?: "待检查"}", color = Muted, fontSize = 11.sp)
                Text(if (task.inspected == null) "旧任务需要重新检查，尚不能确认安装。" else if (task.asset.sha256 == null) "上游未提供摘要；已记录本机文件摘要。技术检查不代表安全认证。" else "上游摘要比对通过；技术检查不代表安全认证。", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("取消", { sourceTask = null })
                    TvButton("确认来源并继续", {
                        if (task.inspected == null) { model.retry(task); model.tell("旧任务需要重新检查安装包，请稍后再次安装") }
                        else { model.confirmSource(task); onInstall(task.copy(sourceConfirmed = true)) }
                        sourceTask = null
                    }, Modifier.testTag("confirm-package-source"), primary = true)
                }
            }
        }
        OrbitDialog(message != null, model::clearMessage) {
            val confirmFocus = remember { FocusRequester() }
            val active = LocalPageActive.current
            LaunchedEffect(active) { if (active) { withFrameNanos { }; confirmFocus.requestFocus() } }
            Text("提示", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(message ?: lastMessage, color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
            TvButton("知道了", model::clearMessage, Modifier.focusRequester(confirmFocus), primary = true)
        }
    }
}

@Composable internal fun AppCard(app: StoreApp, modifier: Modifier = Modifier, onClick: () -> Unit, showRepository: Boolean = false) {
    FocusSurface(onClick, modifier.width(198.dp), radius = 19) { focused ->
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppMark(app, size = 42)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Outlined.NorthEast, null, Modifier.size(15.dp), tint = if (focused) Accent else Muted.copy(.6f))
            }
            Text(app.name, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            Text(app.tagline, color = Muted, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
            Text(if (showRepository) app.repository else app.category, color = Accent, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 7.dp))
        }
    }
}

@Composable private fun BrowsePage(apps: List<StoreApp>, search: Boolean, focus: MutableMap<String, FocusRequester>, navigationFocus: FocusRequester, entryFocus: FocusRequester, onSelect: (StoreApp) -> Unit, returnToken: Int = 0) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("全部") }
    val categories = listOf("全部") + apps.map { it.category }.distinct()
    val filtered = apps.filter { (category == "全部" || it.category == category) &&
        listOf(it.name, it.tagline, it.description, it.repository).any { field -> field.contains(query, true) } }
    val scope = rememberCoroutineScope()
    val grid = remember(returnToken) { LazyGridState() }
    val categoryFocus = remember { mutableMapOf("全部" to entryFocus) }
    val inputFocus = remember { FocusRequester() }
    var lastCategory by rememberSaveable { mutableStateOf("全部") }
    val pageActive = LocalPageActive.current
    LaunchedEffect(returnToken, pageActive) {
        if (pageActive && returnToken > 0) {
            grid.scrollToItem(0); withFrameNanos { }
            val first = filtered.firstOrNull()
            if (first != null) runCatching { focus[first.id]?.requestFocus() } else runCatching { entryFocus.requestFocus() }
        }
    }
    fun categoryRequester() = categoryFocus.getOrPut(lastCategory) { FocusRequester() }
    fun enterResults(): Boolean {
        val first = filtered.firstOrNull() ?: return true
        if (grid.layoutInfo.visibleItemsInfo.any { it.key == first.id }) {
            focus[first.id]?.requestFocus()
        } else scope.launch {
            grid.scrollToItem(0)
            withFrameNanos { }
            focus[first.id]?.requestFocus()
        }
        return true
    }
    Column(Modifier.fillMaxSize().padding(34.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionTitle(if (search) "找到你的下一款好应用" else "每一种热爱，都有选择", "已发现 ${apps.size} 款应用 · 自动分类")
        if (search) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(inputFocus)
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                    Key.DirectionDown -> { categoryRequester().requestFocus(); true }
                    Key.DirectionUp -> { navigationFocus.requestFocus(); true }
                    else -> false
                }
            }, singleLine = true, enabled = LocalPageActive.current,
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("搜索应用名称、用途或仓库", fontSize = 14.sp) },
            shape = RoundedCornerShape(16.dp), textStyle = LocalTextStyle.current.copy(fontSize = 15.sp))
        LazyRow(Modifier.testTag("browse-categories").onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                Key.DirectionDown -> enterResults()
                Key.DirectionUp -> { if (search) inputFocus.requestFocus() else navigationFocus.requestFocus(); true }
                else -> false
            }
        }, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            items(categories, key = { it }) { item -> FocusSurface({ category = item },
                Modifier.testTag("browse-category-$item").focusRequester(categoryFocus.getOrPut(item) { FocusRequester() })
                    .onFocusChanged { if (it.isFocused) lastCategory = item }, selected = category == item, radius = 50) {
                Text(item, Modifier.padding(horizontal = 18.dp, vertical = 10.dp), fontSize = 12.sp)
            } }
        }
        if (filtered.isEmpty()) EmptyState(Icons.Outlined.SearchOff, "没有找到应用", "试试其他关键词或分类", compact = true)
        else LazyVerticalGrid(GridCells.Adaptive(190.dp), Modifier.weight(1f).testTag("browse-grid"), state = grid,
            horizontalArrangement = Arrangement.spacedBy(17.dp), verticalArrangement = Arrangement.spacedBy(17.dp), contentPadding = PaddingValues(7.dp)) {
            items(filtered, key = { it.id }) { app -> AppCard(app, Modifier.fillMaxWidth().animateItem()
                .testTag("browse-app-${app.id}").focusRequester(focus.getOrPut(app.id) { FocusRequester() })
                .onPreviewKeyEvent {
                    val cell = grid.layoutInfo.visibleItemsInfo.firstOrNull { cell -> cell.key == app.id }
                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp && cell?.row == 0) {
                        categoryRequester().requestFocus(); true
                    } else false
                }, { onSelect(app) }) }
        }

    }
}

@Composable internal fun AppDetail(app: StoreApp, installed: InstalledApp?, busy: Boolean, onBack: () -> Unit,
                                 onRefresh: () -> Unit, onDownload: (AppRelease, ReleaseAsset) -> Unit,
                                 onOpen: () -> Unit, onExternal: (String) -> Unit, onUninstall: () -> Unit,
                                 readmes: dev.githubstore.readme.ReadmeRepository? = null, record: UserRepository? = null,
                                 onFavorite: () -> Unit = {}, onSubscribe: () -> Unit = {}, onAssociate: () -> Unit = {}, onHistory: () -> Unit = {}) {
    val readmeBack = remember { ReaderBackController() }
    var readmeOpen by remember { mutableStateOf(false) }
    val readmeFocus = remember { FocusRequester() }
    var restoreReadme by remember { mutableStateOf(false) }
    var selectedTag by rememberSaveable(app.id) { mutableStateOf<String?>(null) }
    var image by remember { mutableStateOf<String?>(null) }
    var previewOpen by remember { mutableStateOf(false) }
    var restoreImageFocus by remember { mutableStateOf(false) }
    val imageFocus = remember { mutableMapOf<String, FocusRequester>() }
    val closeFocus = remember { FocusRequester() }
    var versionsOpen by remember { mutableStateOf(false) }
    var assetsOpen by remember { mutableStateOf(false) }
    val release = selectedRelease(app.releases, selectedTag)
    var selectedAssetId by rememberSaveable(app.id, release?.tag) { mutableStateOf<Long?>(null) }
    val candidates = release?.let { AssetPolicy.candidates(it, Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT) }.orEmpty()
    val asset = candidates.firstOrNull { it.id == selectedAssetId } ?: release?.let { AssetPolicy.recommended(it, Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT) }
    val versionFocus = remember { FocusRequester() }
    val menuLatestFocus = remember { FocusRequester() }
    val detailScroll = rememberLazyListState()
    val firstFocus = remember(app.id) { FocusRequester() }
    val pageActive = LocalPageActive.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(app.id, pageActive) {
        if (pageActive) {
            // A restored lazy viewport may not contain the primary action yet.
            detailScroll.scrollToItem(0)
            withFrameNanos { }
            firstFocus.requestFocus()
        }
    }
    LaunchedEffect(restoreImageFocus, windowFocused, previewOpen, pageActive) {
        if (restoreImageFocus && windowFocused && !previewOpen && pageActive) {
            withFrameNanos { }
            image?.let { imageFocus[it]?.requestFocus() }
            restoreImageFocus = false
        }
    }
    LazyColumn(Modifier.testTag("app-detail"), state = detailScroll, contentPadding = PaddingValues(34.dp), verticalArrangement = Arrangement.spacedBy(25.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TvButton("返回", onBack, icon = { Icon(Icons.Outlined.ArrowBack, null, Modifier.size(16.dp)) })
            Text(app.category, color = Muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            TvButton(if (busy) "正在检查…" else "检查版本", onRefresh)
        } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                AppMark(app, size = 95)
                Column(Modifier.weight(1f)) {
                    Eyebrow("GITHUB · ${app.license}")
                    if (isAuthorAvatar(app.iconUrl)) Text("作者头像 · 尚未识别到项目图标", color = Muted, fontSize = 9.sp)
                    Text(app.name, color = Ink, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp, modifier = Modifier.padding(top = 5.dp))
                    Text(app.tagline, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 19.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val primaryLabel = when {
                            busy -> "正在检查…"
                            release != null && asset != null -> "下载 · ${formatBytes(asset.size)}"
                            release != null && candidates.isNotEmpty() && asset == null -> "选择安装包"
                            release != null -> "查看官方发布"
                            else -> "获取发布版本"
                        }
                        TvButton(primaryLabel, {
                            if (!busy) when {
                                release != null && asset != null -> onDownload(release, asset)
                                release != null && candidates.isNotEmpty() && asset == null -> assetsOpen = true
                                release != null -> onExternal(release.url)
                                else -> onRefresh()
                            }
                        }, Modifier.focusRequester(firstFocus).testTag("detail-primary-download"), primary = true)
                        if (installed != null) TvButton("打开", onOpen)
                        TvButton("官方仓库", { onExternal(app.sourceUrl) })
                    }
                    if (release != null) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box {
                            TvButton("${if (selectedTag == null) "最新" else "版本"}：${release.tag.take(22)} ▾", { onHistory(); versionsOpen = true },
                                Modifier.focusRequester(versionFocus).testTag("version-picker"))
                            DropdownMenu(versionsOpen, { versionsOpen = false }, Modifier.heightIn(max = 240.dp).testTag("version-menu")) {
                                DropdownMenuItem(text = { Text("最新稳定版") }, onClick = { selectedTag = null; versionsOpen = false },
                                    modifier = Modifier.testTag("version-latest").focusRequester(menuLatestFocus))
                                LaunchedEffect(versionsOpen) {
                                    if (versionsOpen) { withFrameNanos { }; menuLatestFocus.requestFocus() }
                                }
                                if (busy) DropdownMenuItem(text = { Text("正在获取历史版本…", fontSize = 12.sp) }, onClick = {}, enabled = false)
                                app.releases.forEach { version ->
                                    DropdownMenuItem(text = { Text(version.tag + if (version.prerelease) " · 测试版" else " · 稳定版", fontSize = 13.sp) },
                                        onClick = { selectedTag = version.tag; versionsOpen = false },
                                        modifier = Modifier.testTag("version-option-${version.tag}"))
                                }
                            }
                        }
                        if (candidates.size > 1 || (candidates.isNotEmpty() && asset == null)) Box {
                            TvButton("安装包：${asset?.abi ?: "请选择"} ▾", { assetsOpen = true }, Modifier.testTag("asset-picker"))
                            DropdownMenu(assetsOpen, { assetsOpen = false }, Modifier.heightIn(max = 240.dp)) {
                                candidates.forEach { candidate ->
                                    DropdownMenuItem(text = { Text("${candidate.name} · ${formatBytes(candidate.size)}", fontSize = 12.sp) },
                                        onClick = { selectedAssetId = candidate.id; assetsOpen = false },
                                        modifier = Modifier.testTag("asset-option-${candidate.id}"))
                                }
                            }
                        }
                    }

                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(vertical = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Fact("当前选择", release?.tag ?: "待获取")
                Fact("下载大小", asset?.let { formatBytes(it.size) } ?: "待选择安装包")
                Fact("发布许可", app.license)
                Fact("安装状态", installed?.versionName ?: "未检测到安装")
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, tint = Muted, modifier = Modifier.size(16.dp))
                Text(app.tvStatus, color = Muted, fontSize = 11.sp)
                if (app.checkedAt > 0) Text("· 检查于 ${date(app.checkedAt)}", color = Muted, fontSize = 10.sp)
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton(if (record?.favorite == true) "取消收藏" else "收藏", onFavorite, Modifier.testTag("detail-favorite"))
            TvButton(if (record?.subscribed == true) "取消订阅" else "订阅版本", onSubscribe, Modifier.testTag("detail-subscribe"))
            TvButton("关联已安装应用", onAssociate)
        } }
        item { Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            SectionTitle("关于应用", "使用上下键逐段浏览", trailing = {
                TvButton("查看 README", { if (readmes != null) readmeOpen = true else onExternal(app.readmeUrl.ifBlank { app.sourceUrl }) }, Modifier.focusRequester(readmeFocus).testTag("open-readme"))
            })
            ReadingText(app.description)
        } }
        item { SectionTitle("应用截图", if (app.screenshots.isEmpty()) "尚未收录真实截图，完整界面可在官方仓库查看" else "${app.name} 项目提供 · 原图展示",
            trailing = if (app.mediaSource.isBlank()) null else { { TvButton("截图来源", { onExternal(app.mediaSource) }) } }) }
        item {
            if (app.screenshots.isEmpty()) Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(17.dp)).background(Panel), contentAlignment = Alignment.Center) {
                TvButton("前往官方项目查看", { onExternal(app.sourceUrl) }, icon = { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(14.dp)) })
            } else LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(7.dp)) {
                itemsIndexed(app.screenshots) { index, url -> FocusSurface({ image = url; previewOpen = true },
                    Modifier.width(260.dp).height(146.dp).focusRequester(imageFocus.getOrPut(url) { FocusRequester() }), radius = 14) {
                    AsyncImage(url, "${app.name} 真实截图 ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } }
            }
        }
        if (app.releases.isEmpty()) item {
            Text(if (busy) "正在从 GitHub 获取发布版本…" else "暂无发布版本，可前往官方仓库查看。", color = Muted, fontSize = 13.sp)
        }
        if (release != null) {
            item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("当前版本", "${release.tag} · ${release.publishedAt.take(10)}")
                if (candidates.isEmpty()) Text("此版本没有适配当前电视的独立 APK，可在官方发布页查看其他平台文件。", color = Muted, fontSize = 12.sp)
                TvButton("查看此版本官方发布页", { onExternal(release.url) })
            } }
            item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("更新说明")
                Text(release.notes.ifBlank { "作者未填写更新说明。" }, color = Muted, fontSize = 12.sp, lineHeight = 21.sp)
            } }
        }
        if (installed != null) item { TvButton("在系统中卸载", onUninstall) }
    }
    OrbitDialog(readmeOpen, { readmeBack.back?.invoke() ?: run { readmeOpen = false } }, width = 900, onClosed = { if (restoreReadme) { runCatching { readmeFocus.requestFocus() }; restoreReadme = false } }) {
        if (readmes != null) ReadmeReader(app, readmes, { restoreReadme = true; readmeOpen = false }, onExternal, readmeBack)
    }
    OrbitDialog(previewOpen, { previewOpen = false }, width = 800,
        onClosed = { if (image != null) restoreImageFocus = true }) {
            val active = LocalPageActive.current
            LaunchedEffect(active) { if (active) { withFrameNanos { }; closeFocus.requestFocus() } }
            AsyncImage(image, "应用截图全屏预览", Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit)
            TvButton("关闭预览", { previewOpen = false }, Modifier.focusRequester(closeFocus))
    }
}

@Composable private fun Fact(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Muted, fontSize = 10.sp)
        Text(value, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable private fun DownloadsPage(tasks: List<DownloadTask>, apps: List<StoreApp>, onInstall: (DownloadTask) -> Unit,
                                     onCancel: (DownloadTask) -> Unit, onRetry: (DownloadTask) -> Unit, onSelect: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(34.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { SectionTitle("下载中心", "下载与安装，每一步都清晰可见") }
        if (tasks.isEmpty()) item { EmptyState(Icons.Outlined.CloudDownload, "好应用，准备就绪", "浏览应用，选择你想带到大屏的那一款") }
        items(tasks.reversed(), key = { it.id }) { task ->
            val progress by animateFloatAsState(task.progress, motionTween(160), label = "download-progress")
            Column(Modifier.fillMaxWidth().animateItem().animateContentSize(motionTween(160)).clip(RoundedCornerShape(20.dp)).background(Panel).padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    apps.firstOrNull { it.id == task.appId }?.let { AppMark(it, size = 43) }
                    Column(Modifier.weight(1f)) {
                        Text(task.appName, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text("${task.releaseTag} · ${task.asset.abi} · ${formatBytes(task.asset.size)}", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                    Text(task.status.label, color = if (task.status == TaskStatus.INSTALLED) Good else Accent, fontSize = 11.sp)
                }
                if (task.active || (task.downloaded > 0 && task.status in setOf(TaskStatus.FAILED, TaskStatus.INTERRUPTED))) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Accent, trackColor = Control)
                    Text("${formatBytes(task.downloaded)} / ${formatBytes(task.asset.size)}", color = Muted, fontSize = 10.sp)
                }
                task.error?.let { Text(it, color = Error, fontSize = 12.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (task.status) {
                        TaskStatus.READY, TaskStatus.WAITING_USER -> TvButton(if (task.status == TaskStatus.READY) "安装应用" else "继续安装", { onInstall(task) }, primary = true)
                        TaskStatus.QUEUED, TaskStatus.DOWNLOADING, TaskStatus.VERIFYING -> TvButton("取消下载", { onCancel(task) })
                        TaskStatus.FAILED, TaskStatus.INTERRUPTED, TaskStatus.CANCELLED -> TvButton(if (task.downloaded > 0 && task.status != TaskStatus.CANCELLED) "继续下载" else "重试", { onRetry(task) }, primary = true)
                        else -> Unit
                    }
                    TvButton("查看应用", { onSelect(task.appId) })
                }
            }
        }
    }
}

@Composable private fun SettingsPage(model: StoreViewModel, apps: List<StoreApp>, onRefresh: () -> Unit, onClear: () -> Unit, onExternal: (String) -> Unit, onAgreement: () -> Unit, onNotices: () -> Unit, onReset: () -> Unit, reduced: Boolean, automaticChecks: Boolean,
                                    onMotion: () -> Unit, onChecks: () -> Unit, onClearData: () -> Unit, onSelfUpdate: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    OrbitDialog(confirmClear, { confirmClear = false }) {
        Text("清除本机项目记录？", fontSize = 20.sp)
        Text("删除收藏、订阅、隐藏偏好与仓库关联，不卸载应用，不删除正在处理的下载任务。此操作不可恢复。", color = Muted, fontSize = 13.sp)
        TvButton("取消", { confirmClear = false })
        TvButton("确认清除记录", { onClearData(); confirmClear = false })
    }
    LazyColumn(contentPadding = PaddingValues(34.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { SectionTitle("简单，也尽在掌控", "Orbit 开源商店 · Android TV") }
        item { val network by model.githubNetwork.collectAsStateWithLifecycle(); GitHubAccountPanel(model.githubAuth, model.deviceFlow, network, onExternal) }
        item { SettingsBlock("本机管理与网络", "自动发现项目 · 只检查订阅和关联应用 · 原始文件来自 GitHub") {
            TvButton("检查我的更新", onRefresh); TvButton("清理文件与图片缓存", onClear)
            TvButton("恢复隐藏项目", onReset)
        } }
        item { SettingsBlock("使用偏好", "设置只保存在这台设备；清缓存不影响收藏和关联") {
            TvButton(if (reduced) "动画：减少" else "动画：跟随系统", onMotion)
            TvButton(if (automaticChecks) "自动检查：开" else "自动检查：关", onChecks)
        } }
        item { SettingsBlock("本机记录", "卸载 Orbit 后本机记录丢失；没有云同步") {
            TvButton("清除本机项目记录", { confirmClear = true })
        } }
        item { SettingsBlock("关于 Orbit", "版本 ${BuildConfig.VERSION_NAME} · 独立开源项目，与 GitHub 无官方关联") {
            TvButton("用户协议与风险告知", onAgreement)
            TvButton("阅读组件开源许可", onNotices)
            TvButton("检查商店更新", onSelfUpdate)
            TvButton("项目主页与反馈", { onExternal("https://github.com/DurianLollipop/github-app-store") })
        } }
        item { Column(Modifier.background(Panel, RoundedCornerShape(20.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("你的应用，留在你的设备。", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Medium)
            Text("游客可用，也可自愿授权 GitHub。应用目录、下载任务和安装记录保存在本机。网络请求直接访问 GitHub 及项目图片来源，遵循相应服务的隐私政策。当前版本未接入广告和分析服务。", color = Muted, fontSize = 12.sp, lineHeight = 22.sp)
            Text("开发预览：应用的 TV 真机兼容性仍待验证。历史记录以 GitHub 当前可用资产为准；商店更新需要项目发布同签名 APK。", color = Muted, fontSize = 11.sp, lineHeight = 20.sp)
        } }
    }
}

@Composable private fun SettingsBlock(title: String, subtitle: String, actions: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

@Composable private fun EmptyState(icon: ImageVector, title: String, subtitle: String, compact: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = if (compact) 12.dp else 60.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 15.dp)) {
        Icon(icon, null, tint = Accent.copy(.8f), modifier = Modifier.size(if (compact) 28.dp else 45.dp))
        Text(title, color = Ink, fontSize = if (compact) 19.sp else 23.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

private fun date(timestamp: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
