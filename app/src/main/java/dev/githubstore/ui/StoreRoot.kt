package dev.githubstore.ui

import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.githubstore.BuildConfig
import dev.githubstore.StoreViewModel
import dev.githubstore.domain.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Destination(val title: String, val icon: ImageVector) {
    Discover("发现", Icons.Outlined.Explore), Search("搜索", Icons.Outlined.Search),
    Categories("分类", Icons.Outlined.GridView), Downloads("下载", Icons.Outlined.FileDownload),
    Library("应用", Icons.Outlined.Apps), Settings("设置", Icons.Outlined.Settings)
}

@Composable fun StoreRoot(model: StoreViewModel, onInstall: (DownloadTask) -> Unit,
                         onDownloadPermission: () -> Unit, openDownloads: Boolean,
                         onExternal: (String) -> Unit, onUninstall: (String) -> Unit) {
    val catalogApps by model.apps.collectAsStateWithLifecycle()
    val apps = catalogApps.filter { it.id != "orbit-store" }
    val tasks by model.tasks.collectAsStateWithLifecycle()
    val installed by model.installed.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val ignored by model.ignored.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(if (openDownloads) Destination.Downloads else Destination.Discover) }
    var selectedApp by rememberSaveable { mutableStateOf<String?>(null) }
    var lastAppId by rememberSaveable { mutableStateOf<String?>(null) }
    val cardFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun select(app: StoreApp) { lastAppId = app.id; selectedApp = app.id }
    val homeScroll = rememberLazyListState()
    val railFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { railFocus.requestFocus() }
    LaunchedEffect(selectedApp) {
        if (selectedApp == null && lastAppId != null) {
            withFrameNanos { }
            runCatching { cardFocus[lastAppId]?.requestFocus() }
        }
    }
    BackHandler(selectedApp != null || destination != Destination.Discover) {
        if (selectedApp != null) selectedApp = null else destination = Destination.Discover
    }
    OrbitTheme {
        Row(Modifier.fillMaxSize().background(Backdrop)) {
            NavigationRail(destination, tasks.count { it.active }, railFocus) { destination = it; selectedApp = null }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val app = catalogApps.firstOrNull { it.id == selectedApp }
                if (app != null) AppDetail(app, installed[app.packageName], busy.contains(app.id),
                    onBack = { selectedApp = null }, onRefresh = { model.refresh(app.id, true) },
                    onDownload = { release, asset -> onDownloadPermission(); model.download(app, release, asset); selectedApp = null; destination = Destination.Downloads },
                    onOpen = { model.open(app) }, onExternal = onExternal,
                    onUninstall = { onUninstall(app.packageName) })
                else when (destination) {
                    Destination.Discover -> DiscoverPage(apps, homeScroll, cardFocus, ::select,
                        onHeroSelect = { lastAppId = "hero"; selectedApp = it.id })
                    Destination.Search -> BrowsePage(apps, true, cardFocus, ::select)
                    Destination.Categories -> BrowsePage(apps, false, cardFocus, ::select)
                    Destination.Downloads -> DownloadsPage(tasks, catalogApps, onInstall, model::cancel, model::retry, { selectedApp = it })
                    Destination.Library -> LibraryPage(apps, installed, ignored, busy.isNotEmpty(), model::refreshAll,
                        onSelect = { selectedApp = it.id }, onOpen = model::open, onIgnore = model::ignore)
                    Destination.Settings -> SettingsPage(apps, model::refreshAll, model::clearCache, onExternal,
                        onSelfUpdate = { selectedApp = "orbit-store"; model.refresh("orbit-store", true) })
                }
            }
        }
        if (message != null) AlertDialog(onDismissRequest = model::clearMessage,
            containerColor = Panel, title = { Text("提示", color = Ink) }, text = { Text(message!!, color = Muted, fontSize = 14.sp) },
            confirmButton = { TvButton("知道了", model::clearMessage, primary = true) })
    }
}

@Composable private fun NavigationRail(selected: Destination, activeDownloads: Int, firstFocus: FocusRequester, onSelect: (Destination) -> Unit) {
    Column(Modifier.width(82.dp).fillMaxHeight().background(Color(0xFF0D1017)).padding(horizontal = 12.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.BlurCircular, "Orbit", tint = Ink, modifier = Modifier.size(29.dp))
        Text("ORBIT", color = Muted, fontSize = 8.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 7.dp, bottom = 25.dp))
        Destination.entries.forEachIndexed { index, item ->
            if (item == Destination.Settings) Spacer(Modifier.weight(1f))
            FocusSurface({ onSelect(item) }, Modifier.fillMaxWidth().padding(vertical = 4.dp).then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                selected = selected == item, color = Color.Transparent, radius = 14, scale = 1.04f) { focused ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(item.icon, null, Modifier.size(21.dp), tint = if (focused) Backdrop else if (selected == item) Ink else Muted)
                    Text(if (item == Destination.Downloads && activeDownloads > 0) "下载 $activeDownloads" else item.title,
                        color = if (focused) Backdrop else if (selected == item) Ink else Muted, fontSize = 9.sp,
                        modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
}

@Composable private fun DiscoverPage(apps: List<StoreApp>, scroll: LazyListState, focus: MutableMap<String, FocusRequester>, onSelect: (StoreApp) -> Unit, onHeroSelect: (StoreApp) -> Unit) {
    LazyColumn(state = scroll, contentPadding = PaddingValues(start = 34.dp, end = 34.dp, top = 25.dp, bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(25.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("发现", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Outlined.Code, null, tint = Muted, modifier = Modifier.size(13.dp))
                Text("  源自 GitHub，始于热爱", color = Muted, fontSize = 10.sp)
            }
        }
        item {
            Box(Modifier.fillMaxWidth().height(244.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF171E2D), Color(0xFF101725), Color(0xFF101826))))) {
                OrbitArtwork(Modifier.align(Alignment.CenterEnd).fillMaxWidth(.5f).fillMaxHeight())
                Column(Modifier.fillMaxHeight().padding(horizontal = 29.dp, vertical = 22.dp), verticalArrangement = Arrangement.Center) {
                    Eyebrow("OPEN SOURCE. OPEN POSSIBILITIES.")
                    Text("好应用，\n自由探索。", color = Ink, fontSize = 36.sp, lineHeight = 43.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = (-1.3).sp, modifier = Modifier.padding(top = 11.dp))
                    Text("让开源的精彩，来到你的大屏。", color = Color(0xFFA9B4C7), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 17.dp))
                    apps.firstOrNull()?.let { app -> TvButton("探索 ${app.name}", { onHeroSelect(app) },
                        modifier = Modifier.focusRequester(focus.getOrPut("hero") { FocusRequester() }), primary = true,
                        icon = { Icon(Icons.Outlined.ArrowForward, null, Modifier.size(15.dp)) }) }
                }
                Text("开发预览 · TV 兼容性待实测", color = Muted.copy(.8f), fontSize = 8.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
            }
        }
        item { SectionTitle("值得一试", "从影音到工具，发现开源的更多可能") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(3.dp)) {
                items(apps, key = { it.id }) { app -> AppCard(app, Modifier.focusRequester(focus.getOrPut(app.id) { FocusRequester() }), onClick = { onSelect(app) }) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VerifiedUser, null, tint = Accent, modifier = Modifier.size(26.dp))
                Column(Modifier.padding(start = 15.dp).weight(1f)) {
                    Text("每一次下载，来源清晰。", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("连接作者的官方发布，保留你的版本选择权。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
}

@Composable private fun AppCard(app: StoreApp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(onClick, modifier.width(198.dp), radius = 19) { focused ->
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppMark(app, size = 48)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Outlined.NorthEast, null, Modifier.size(15.dp), tint = if (focused) Color(0xFF58647B) else Muted.copy(.6f))
            }
            Text(app.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 15.dp))
            Text(app.tagline, color = if (focused) Color(0xFF5C6371) else Muted, fontSize = 10.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
            Text(app.category, color = if (focused) Color(0xFF456086) else Accent, fontSize = 9.sp, modifier = Modifier.padding(top = 11.dp))
        }
    }
}

@Composable private fun BrowsePage(apps: List<StoreApp>, search: Boolean, focus: MutableMap<String, FocusRequester>, onSelect: (StoreApp) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("全部") }
    val categories = listOf("全部") + apps.map { it.category }.distinct()
    val filtered = apps.filter { (category == "全部" || it.category == category) &&
        listOf(it.name, it.tagline, it.description, it.repository).any { field -> field.contains(query, true) } }
    Column(Modifier.fillMaxSize().padding(34.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionTitle(if (search) "找到你的下一款好应用" else "每一种热爱，都有选择", "浏览目录中的 ${apps.size} 款开源应用")
        if (search) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("搜索应用名称、用途或仓库", fontSize = 14.sp) },
            shape = RoundedCornerShape(16.dp), textStyle = LocalTextStyle.current.copy(fontSize = 15.sp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            items(categories) { item -> FocusSurface({ category = item }, selected = category == item, radius = 50) {
                Text(item, Modifier.padding(horizontal = 18.dp, vertical = 10.dp), fontSize = 12.sp)
            } }
        }
        if (filtered.isEmpty()) EmptyState(Icons.Outlined.SearchOff, "没有找到应用", "试试其他关键词或分类")
        else LazyVerticalGrid(GridCells.Adaptive(190.dp), horizontalArrangement = Arrangement.spacedBy(17.dp), verticalArrangement = Arrangement.spacedBy(17.dp), contentPadding = PaddingValues(4.dp)) {
            items(filtered, key = { it.id }) { app -> AppCard(app, Modifier.fillMaxWidth().focusRequester(focus.getOrPut(app.id) { FocusRequester() }), { onSelect(app) }) }
        }
    }
}

@Composable private fun AppDetail(app: StoreApp, installed: InstalledApp?, busy: Boolean, onBack: () -> Unit,
                                 onRefresh: () -> Unit, onDownload: (AppRelease, ReleaseAsset) -> Unit,
                                 onOpen: () -> Unit, onExternal: (String) -> Unit, onUninstall: () -> Unit) {
    var selectedTag by rememberSaveable(app.id) { mutableStateOf<String?>(null) }
    var image by remember { mutableStateOf<String?>(null) }
    val release = app.releases.firstOrNull { it.tag == selectedTag } ?: app.releases.firstOrNull { !it.prerelease } ?: app.releases.firstOrNull()
    val asset = release?.let { AssetPolicy.recommended(it, Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT) }
    val firstFocus = remember(app.id) { FocusRequester() }
    LaunchedEffect(app.id) { firstFocus.requestFocus() }
    LazyColumn(contentPadding = PaddingValues(34.dp), verticalArrangement = Arrangement.spacedBy(25.dp)) {
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
                    Text(app.name, color = Ink, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp, modifier = Modifier.padding(top = 5.dp))
                    Text(app.tagline, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 19.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (release != null && asset != null && app.certificateSha256.isEmpty()) TvButton("查看官方发布", { onExternal(release.url) }, Modifier.focusRequester(firstFocus), primary = true)
                        else if (release != null && asset != null) TvButton("下载 · ${formatBytes(asset.size)}", { onDownload(release, asset) },
                            Modifier.focusRequester(firstFocus), primary = true,
                            icon = { Icon(Icons.Outlined.Download, null, Modifier.size(16.dp)) })
                        else TvButton(if (busy) "正在检查…" else "获取发布版本", onRefresh, Modifier.focusRequester(firstFocus), primary = true)
                        if (installed != null) TvButton("打开", onOpen)
                        TvButton("官方仓库", { onExternal(app.sourceUrl) })
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
        item { Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            SectionTitle("关于应用")
            Text(app.description, color = Color(0xFFB6BDCB), fontSize = 13.sp, lineHeight = 23.sp)
        } }
        item { SectionTitle("应用截图", if (app.screenshots.isEmpty()) "尚未收录真实截图，完整界面可在官方仓库查看" else "${app.name} 项目提供 · 原图展示",
            trailing = if (app.mediaSource.isBlank()) null else { { TvButton("截图来源", { onExternal(app.mediaSource) }) } }) }
        item {
            if (app.screenshots.isEmpty()) Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(17.dp)).background(Panel), contentAlignment = Alignment.Center) {
                TvButton("前往官方项目查看", { onExternal(app.sourceUrl) }, icon = { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(14.dp)) })
            } else LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(3.dp)) {
                items(app.screenshots) { url -> FocusSurface({ image = url }, Modifier.width(260.dp).height(146.dp), radius = 14) {
                    AsyncImage(url, "${app.name} 真实截图", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } }
            }
        }
        item { SectionTitle("版本记录", "最多查看最近 20 个发布；历史版本受系统兼容与降级限制") }
        if (app.releases.isEmpty()) item { TvButton("从 GitHub 获取版本", onRefresh, primary = true) }
        items(app.releases, key = { it.id }) { version ->
            FocusSurface({ selectedTag = version.tag }, Modifier.fillMaxWidth(), selected = version.tag == release?.tag, radius = 16) { focused ->
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(version.tag + if (version.prerelease) "  · 测试版" else "  · 稳定版", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("${version.publishedAt.take(10)}  · ${version.assets.size} 个 APK", color = if (focused) Color(0xFF647087) else Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                    Icon(if (version.tag == release?.tag) Icons.Outlined.CheckCircle else Icons.Outlined.ChevronRight, null, Modifier.size(19.dp))
                }
            }
        }
        if (release != null) {
            item { Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                SectionTitle("${release.tag} 的安装包", "架构会自动筛选；文件校验及签名检查通过后才可安装")
                if (AssetPolicy.candidates(release, Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT).isEmpty()) Text("这个版本没有适合当前设备的独立 APK。", color = Muted, fontSize = 13.sp)
                AssetPolicy.candidates(release, Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT).forEach { candidate ->
                    FocusSurface({ if (app.certificateSha256.isEmpty()) onExternal(release.url) else onDownload(release, candidate) }, Modifier.fillMaxWidth(), radius = 13) { focused ->
                        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(candidate.name, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${candidate.abi} · ${formatBytes(candidate.size)} · ${if (candidate.versionCode != null) "包信息已核对" else "安装前核对兼容性"}",
                                    color = if (focused) Color(0xFF647087) else Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                            }
                            Icon(Icons.Outlined.Download, null, Modifier.size(19.dp))
                        }
                    }
                }
                TvButton("查看此版本官方发布页", { onExternal(release.url) })
            } }
            item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("更新说明")
                Text(release.notes.ifBlank { "作者未填写更新说明。" }, color = Muted, fontSize = 12.sp, lineHeight = 21.sp)
            } }
        }
        if (installed != null) item { TvButton("在系统中卸载", onUninstall) }
    }
    if (image != null) Dialog(onDismissRequest = { image = null }) {
        Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(image, "应用截图全屏预览", Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit)
            TvButton("关闭预览", { image = null })
        }
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
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    apps.firstOrNull { it.id == task.appId }?.let { AppMark(it, size = 43) }
                    Column(Modifier.weight(1f)) {
                        Text(task.appName, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text("${task.releaseTag} · ${task.asset.abi} · ${formatBytes(task.asset.size)}", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                    Text(task.status.label, color = if (task.status == TaskStatus.INSTALLED) Good else Accent, fontSize = 11.sp)
                }
                if (task.active) {
                    LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Accent, trackColor = Color(0xFF293142))
                    Text("${formatBytes(task.downloaded)} / ${formatBytes(task.asset.size)}", color = Muted, fontSize = 10.sp)
                }
                task.error?.let { Text(it, color = Color(0xFFE6B3AC), fontSize = 12.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (task.status) {
                        TaskStatus.READY, TaskStatus.WAITING_USER -> TvButton(if (task.status == TaskStatus.READY) "安装应用" else "继续安装", { onInstall(task) }, primary = true)
                        TaskStatus.QUEUED, TaskStatus.DOWNLOADING, TaskStatus.VERIFYING -> TvButton("取消下载", { onCancel(task) })
                        TaskStatus.FAILED, TaskStatus.INTERRUPTED, TaskStatus.CANCELLED -> TvButton("重试", { onRetry(task) }, primary = true)
                        else -> Unit
                    }
                    TvButton("查看应用", { onSelect(task.appId) })
                }
            }
        }
    }
}

@Composable private fun LibraryPage(apps: List<StoreApp>, installed: Map<String, InstalledApp>, ignored: Set<String>, busy: Boolean,
                                  onRefresh: () -> Unit, onSelect: (StoreApp) -> Unit, onOpen: (StoreApp) -> Unit,
                                  onIgnore: (StoreApp, ReleaseAsset) -> Unit) {
    val localApps = apps.filter { it.packageName in installed }
    LazyColumn(contentPadding = PaddingValues(34.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { SectionTitle("我的应用", "管理目录中可识别的应用与更新", trailing = { TvButton(if (busy) "正在检查…" else "检查更新", onRefresh) }) }
        if (localApps.isEmpty()) item { EmptyState(Icons.Outlined.Widgets, "让大屏，有更多可能", "尚未检测到目录内的已安装应用，先去发现一款吧") }
        items(localApps, key = { it.id }) { app ->
            val current = installed.getValue(app.packageName)
            val latest = app.releases.firstOrNull { !it.prerelease }
            val asset = latest?.let { AssetPolicy.recommended(it, Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT) }
            val isIgnored = asset?.let { "${app.id}:${it.versionCode ?: it.id}" in ignored } ?: false
            val upgrade = asset?.let { AssetPolicy.upgradeAvailable(current, it, app.certificateSha256) } ?: false
            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(19.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppMark(app, size = 48)
                    Column(Modifier.weight(1f)) {
                        Text(app.name, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text("已安装 ${current.versionName} · ${current.versionCode}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                    Text(when { upgrade && isIgnored -> "已忽略此更新"; upgrade -> "可更新至 ${latest?.tag}"; asset?.versionCode == null && latest != null -> "上游版本 ${latest.tag}，待核对"; else -> "已检查的版本无更新" }, color = if (upgrade && !isIgnored) Good else Muted, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton(if (upgrade && !isIgnored) "查看更新" else "版本与详情", { onSelect(app) }, primary = upgrade && !isIgnored)
                    TvButton("打开", { onOpen(app) })
                    if (upgrade && asset != null) TvButton(if (isIgnored) "恢复提醒" else "忽略此版本", { onIgnore(app, asset) })
                }
            }
        }
        item { Text("应用识别受系统包可见性限制，仅显示当前目录中的应用。检查失败时请重试。", color = Muted, fontSize = 10.sp) }
    }
}

@Composable private fun SettingsPage(apps: List<StoreApp>, onRefresh: () -> Unit, onClear: () -> Unit, onExternal: (String) -> Unit, onSelfUpdate: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(34.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { SectionTitle("简单，也尽在掌控", "Orbit 开源商店 · Android TV") }
        item { SettingsBlock("目录与网络", "${apps.size} 个开发样本 · 原始文件来自 GitHub 官方发布") {
            TvButton("刷新全部版本", onRefresh); TvButton("清理下载缓存", onClear)
        } }
        item { SettingsBlock("关于 Orbit", "版本 ${BuildConfig.VERSION_NAME} · 独立开源项目，与 GitHub 无官方关联") {
            TvButton("检查商店更新", onSelfUpdate)
            TvButton("项目主页与反馈", { onExternal("https://github.com/DurianLollipop/github-app-store") })
        } }
        item { Column(Modifier.background(Panel, RoundedCornerShape(20.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("你的应用，留在你的设备。", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Medium)
            Text("无需账户。应用目录、下载任务和安装记录保存在本机。网络请求直接访问 GitHub 及项目图片来源，遵循相应服务的隐私政策。当前版本未接入广告和分析服务。", color = Muted, fontSize = 12.sp, lineHeight = 22.sp)
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

@Composable private fun EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 60.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Icon(icon, null, tint = Accent.copy(.8f), modifier = Modifier.size(45.dp))
        Text(title, color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

private fun date(timestamp: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
