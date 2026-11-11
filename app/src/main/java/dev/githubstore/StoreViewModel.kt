package dev.githubstore

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.githubstore.domain.*
import dev.githubstore.data.*
import java.net.URI
import coil.imageLoader
import dev.githubstore.download.DownloadService
import dev.githubstore.download.DownloadEngine
import dev.githubstore.install.InstallManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class GitHubSearchState(val query: String = "", val results: List<StoreApp> = emptyList(),
    val loading: Boolean = false, val loadingMore: Boolean = false, val total: Int = 0,
    val page: Int = 0, val hasMore: Boolean = false, val error: String? = null, val incomplete: Boolean = false, val openRepository: StoreApp? = null, val retryMore: Boolean = false)

class StoreViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application as StoreApplication
    val library = store.library.records
    val readmes = store.readmes
    val agreement = store.agreement
    val githubAuth = store.auth
    val githubNetwork = store.github.transport.state
    val deviceFlow = store.deviceFlow
    val apps = combine(store.catalog.apps, library) { seed, records ->
        (records.map { r -> r.app.let { a -> r.bindings.firstOrNull()?.identity?.let { a.copy(packageName = it.packageName, certificateSha256 = it.certificates) } ?: a } } + seed).distinctBy { it.id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, store.catalog.apps.value)
    private val _home = MutableStateFlow(DiscoverySnapshot())
    val home = _home.asStateFlow()
    private val _homeBusy = MutableStateFlow(false)
    val homeBusy = _homeBusy.asStateFlow()
    var mode = DiscoveryMode.valueOf(application.getSharedPreferences("discovery", 0).getString("mode", "PERSONAL") ?: "PERSONAL"); private set
    var hotSort = HotSort.valueOf(application.getSharedPreferences("discovery", 0).getString("sort", "GROWTH") ?: "GROWTH"); private set
    var hotPeriod = HotPeriod.valueOf(application.getSharedPreferences("discovery", 0).getString("period", "WEEK") ?: "WEEK"); private set
    private var homeJob: Job? = null
    val discoverySettings = MutableStateFlow(Triple(mode, hotSort, hotPeriod))
    fun homeMode(mode: DiscoveryMode = this.mode, sort: HotSort = hotSort, period: HotPeriod = hotPeriod) {
        this.mode = mode; hotSort = sort; hotPeriod = period
        discoverySettings.value = Triple(mode, sort, period)
        store.getSharedPreferences("discovery", 0).edit().putString("mode", mode.name).putString("sort", sort.name).putString("period", period.name).apply()
        refreshHome()
    }
    private var visibleJob: Job? = null
    private val presentationAttempts = mutableMapOf<String, Long>()
    fun pauseDiscovery() { homeJob?.cancel(); visibleJob?.cancel() }
    fun enrichVisibleHome(ids: List<String>) {
        if (_homeBusy.value || ids.isEmpty()) return
        visibleJob?.cancel()
        val settings = discoverySettings.value
        visibleJob = viewModelScope.launch {
            withContext(Dispatchers.IO + GitHubRequestPolicy(background = true) + RequestBudget(12)) {
                for (id in ids.take(12)) {
                    val item = _home.value.items.firstOrNull { it.app.id == id } ?: continue
                    val saved = store.library.find(id)?.app
                    if (saved?.readmeUrl?.isNotBlank() == true) continue
                    val now = System.currentTimeMillis()
                    if (now - (presentationAttempts[id] ?: 0) < 5 * 60_000) continue
                    presentationAttempts[id] = now
                    try {
                        val presentation = store.github.presentation(item.app)
                        val app = store.library.remember(if (saved != null) presentation.copy(checkedAt = saved.checkedAt, releases = saved.releases) else presentation)
                        ensureActive()
                        if (discoverySettings.value == settings) _home.value = _home.value.copy(items = _home.value.items.map { if (it.app.id == id) it.copy(app = app) else it })
                    } catch (c: CancellationException) { presentationAttempts.remove(id); throw c }
                    catch (e: Exception) {
                        if (discoverySettings.value == settings) _home.value = _home.value.copy(error = "部分项目资料暂未补全：${e.message}")
                        if (e is GitHubRateLimitException || e.message?.contains("预算") == true) break
                    }
                }
            }
        }
    }
    fun refreshHome(force: Boolean = false) {
        homeJob?.cancel(); visibleJob?.cancel()
        val selectedMode = mode; val selectedSort = hotSort; val selectedPeriod = hotPeriod
        homeJob = viewModelScope.launch {
            _homeBusy.value = true
            try {
                val cached = withContext(Dispatchers.IO) { store.discovery.cached(selectedMode, selectedSort, selectedPeriod) }
                ensureActive()
                _home.value = cached.copy(items = cached.items.filterNot { store.library.find(it.app.id)?.hidden == true })
                val loaded = withContext(Dispatchers.IO) { store.discovery.load(selectedMode, selectedSort, selectedPeriod, force) { partial ->
                    if (selectedMode == mode && selectedSort == hotSort && selectedPeriod == hotPeriod) {
                        _home.value = partial.copy(items = partial.items.map { item ->
                            val known = store.library.findRepository(item.app.repository)?.app
                            if (known != null) item.copy(app = item.app.copy(id = known.id, iconUrl = known.iconUrl.ifBlank { item.app.iconUrl })) else item
                        })
                    }
                } }
                val savedApps = withContext(Dispatchers.IO) { store.library.rememberAll(loaded.items.map { it.app }) }
                ensureActive()
                val snapshot = loaded.copy(items = loaded.items.zip(savedApps) { item, app -> item.copy(app = app) })
                val filtered = snapshot.items.filterNot { item -> store.library.find(item.app.id)?.let { record ->
                    record.hidden || (selectedMode != DiscoveryMode.GLOBAL && record.bindings.any { store.inspector.installed(it.identity.packageName) != null })
                } == true }
                _home.value = snapshot.copy(items = if (selectedMode == DiscoveryMode.PERSONAL) Recommendation.rank(filtered, store.library.records.value, System.currentTimeMillis()) else filtered)
            } finally { _homeBusy.value = false }
        }
    }
    fun favorite(app: StoreApp) = viewModelScope.launch(Dispatchers.IO) { store.library.mutate(app.id) { r -> (r ?: UserRepository(app)).let { it.copy(favorite = !it.favorite) } } }
    fun subscribe(app: StoreApp) = viewModelScope.launch(Dispatchers.IO) { store.library.mutate(app.id) { r -> (r ?: UserRepository(app)).let { it.copy(subscribed = !it.subscribed) } } }
    fun installChecks(app: StoreApp, packageName: String) = viewModelScope.launch(Dispatchers.IO) { store.library.mutate(app.id) { r -> (r ?: UserRepository(app)).let { it.copy(bindings = it.bindings.map { b -> if (b.identity.packageName == packageName) b.copy(checks = !b.checks) else b }) } } }
    fun bindingChannel(app: StoreApp, packageName: String) = viewModelScope.launch(Dispatchers.IO) { store.library.mutate(app.id) { r -> (r ?: UserRepository(app)).let { it.copy(bindings = it.bindings.map { b -> if (b.identity.packageName == packageName) b.copy(prerelease = !b.prerelease) else b }) } } }
    fun prerelease(app: StoreApp) = viewModelScope.launch(Dispatchers.IO) { store.library.mutate(app.id) { r -> (r ?: UserRepository(app)).let { it.copy(prerelease = !it.prerelease) } } }
    fun hide(app: StoreApp) = viewModelScope.launch(Dispatchers.IO) { store.library.mutate(app.id) { r -> (r ?: UserRepository(app)).copy(hidden = true) } }
    fun resetPreferences() { store.library.records.value.forEach { r -> if (r.hidden) store.library.mutate(r.app.id) { r.copy(hidden = false) } }; tell("已恢复隐藏项目") }
    fun unlink(app: StoreApp, packageName: String) = viewModelScope.launch(Dispatchers.IO) { store.library.mutate(app.id) { r -> (r ?: UserRepository(app)).let { it.copy(bindings = it.bindings.filterNot { b -> b.identity.packageName == packageName }) } } }
    fun associate(app: StoreApp) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val task = tasks.value.lastOrNull { it.appId == app.id && it.inspected != null } ?: error("请先下载对应 APK 以核对包名和签名，再关联外部安装")
            val current = store.inspector.installed(task.inspected!!.packageName) ?: error("系统未检测到这个包；可见性受系统限制")
            check(current.certificates == task.inspected.certificates) { "已安装签名与仓库安装包不同，不能关联" }
            store.library.bind(app, current, task.asset.name, task.releaseTag, task.prerelease); refreshInstalled()
            tell("已关联 ${current.packageName}")
        } catch (e: Exception) { tell(e.message ?: "关联失败") }
    }
    val tasks = store.tasks.tasks
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _installed = MutableStateFlow<Map<String, InstalledApp>>(emptyMap())
    val installed = _installed.asStateFlow()
    private val preferences = application.getSharedPreferences("preferences", 0)
    private val _ignored = MutableStateFlow(preferences.getStringSet("ignored", emptySet()).orEmpty().toSet())
    val ignored = _ignored.asStateFlow()
    val reducedMotion = MutableStateFlow(preferences.getBoolean("reducedMotion", false))
    val automaticChecks = MutableStateFlow(preferences.getBoolean("automaticChecks", true))
    fun toggleMotion() { reducedMotion.value = !reducedMotion.value; preferences.edit().putBoolean("reducedMotion", reducedMotion.value).apply() }
    fun toggleChecks() { automaticChecks.value = !automaticChecks.value; preferences.edit().putBoolean("automaticChecks", automaticChecks.value).apply() }
    fun clearUserData() { store.library.clear(); store.readmes.clearCache(); preferences.edit().remove("ignored").apply(); _ignored.value = emptySet(); refreshInstalled(); tell("已清除本机收藏、订阅和关联；未卸载任何应用") }
    private val installer = InstallManager(store)

    private val _search = MutableStateFlow(GitHubSearchState())
    val search = _search.asStateFlow()
    private var searchJob: Job? = null
    private val _discovered = MutableStateFlow<List<StoreApp>>(emptyList())
    val discovered = _discovered.asStateFlow()
    private val searchCache = object : LinkedHashMap<String, RepositoryPage>(20, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RepositoryPage>?) = size > 20
    }
    fun searchQuery(query: String) { runSearch(query, immediate = false) }
    fun consumeRepositoryOpen() { _search.value = _search.value.copy(openRepository = null) }
    fun submitSearch() { runSearch(_search.value.query, immediate = true) }
    private fun known(app: StoreApp) = store.library.findRepository(app.repository)?.app ?: app
    private fun directRepository(query: String): String? {
        val value = query.trim()
        val name = if (value.startsWith("https://")) {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (uri.host != "github.com" || uri.userInfo != null) return null
            uri.path.trim('/').removeSuffix(".git")
        } else value
        return name.takeIf { Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(it) }
    }
    private fun runSearch(query: String, immediate: Boolean) {
        searchJob?.cancel()
        if (query.isBlank()) { _search.value = GitHubSearchState(query); return }
        val previous = _search.value.takeIf { it.query.trim() == query.trim() && it.results.isNotEmpty() }
        val key = "${query.trim()}:1"
        _search.value = previous?.copy(loading = true, error = null, openRepository = null, retryMore = false) ?: GitHubSearchState(query, loading = true)
        searchJob = viewModelScope.launch {
            if (!immediate) delay(800)
            try {
                val direct = directRepository(query)
                val result = if (direct != null) RepositoryPage(listOf(store.github.repository(direct)), 1, false) else if (query.trim().startsWith("https://")) error("只支持 GitHub 的仓库地址，或输入关键词搜索") else if (!immediate) searchCache[key] ?: store.github.search(query.trim()) else store.github.search(query.trim())
                ensureActive()
                searchCache[key] = result
                _search.value = GitHubSearchState(query, result.apps.map(::known), total = result.total,
                    page = 1, hasMore = result.hasMore, incomplete = result.incomplete, openRepository = if (immediate && direct != null) result.apps.firstOrNull()?.let(::known) else null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val saved = searchCache[key]
                val fallback = previous ?: saved?.let { GitHubSearchState(query, it.apps.map(::known), total = it.total, hasMore = it.hasMore, incomplete = it.incomplete) }
                val message = error.message ?: "无法连接 GitHub，请检查网络后重试"
                _search.value = fallback?.copy(loading = false, loadingMore = false, error = "$message · 已保留此前结果", openRepository = null, retryMore = false) ?: GitHubSearchState(query, error = message)
            }
        }
    }
    fun moreSearchResults() {
        val state = _search.value
        if (state.loading || state.loadingMore || !state.hasMore) return
        _search.value = state.copy(loadingMore = true, error = null, retryMore = false)
        searchJob = viewModelScope.launch {
            try {
                val result = store.github.search(state.query.trim(), state.page + 1)
                ensureActive()
                _search.value = state.copy(results = (state.results + result.apps.map(::known)).distinctBy { it.id },
                    page = state.page + 1, hasMore = result.hasMore, loadingMore = false, total = result.total, error = null, retryMore = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _search.value = state.copy(error = error.message ?: "加载失败，请重试", retryMore = true) }
        }
    }
    fun prepareDetail(app: StoreApp, force: Boolean = false) {
        val curated = store.catalog.apps.value.any { it.id == app.id }
        val current = apps.value.firstOrNull { it.id == app.id } ?: _discovered.value.firstOrNull { it.id == app.id } ?: app
        if (!curated) _discovered.value = (_discovered.value.filterNot { it.id == app.id } + current).takeLast(20)
        if (app.id in _busy.value || (!force && current.checkedAt > System.currentTimeMillis() - 5 * 60_000)) return
        _busy.value += app.id
        viewModelScope.launch {
            try {
                val identified = if (current.githubId == 0L && current.id != "orbit-store") store.github.repository(current.repository).copy(id = current.id) else current
                val details = withContext(Dispatchers.IO + GitHubRequestPolicy(revalidate = force)) { store.library.remember(store.github.details(identified).let { it.copy(category = DiscoveryRules.category(it)) }) }
                if (curated) store.catalog.acceptDetails(details)
                else _discovered.value = (_discovered.value.filterNot { it.id == app.id } + details).takeLast(20)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { tell(error.message ?: "无法获取仓库详情，请重试") }
            finally { _busy.value -= app.id }
        }
    }

    fun loadHistory(app: StoreApp) {
        if (app.id in _busy.value) return
        _busy.value += app.id
        viewModelScope.launch {
            try {
                val versions = store.github.history(app)
                val updated = withContext(Dispatchers.IO) { store.library.remember(app.copy(releases = versions)) }
                if (store.catalog.apps.value.any { it.id == app.id }) store.catalog.acceptDetails(updated)
                else _discovered.value = (_discovered.value.filterNot { it.id == app.id } + updated).takeLast(20)
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) { tell(e.message ?: "历史版本获取失败") }
            finally { _busy.value -= app.id }
        }
    }
    init { refreshInstalled(); refreshHome() }
    fun tell(message: String) { _message.value = message }
    fun clearMessage() { _message.value = null }
    fun refreshInstalled() = viewModelScope.launch(Dispatchers.IO) {
        _installed.value = (apps.value.map { it.packageName } + store.library.records.value.flatMap { it.bindings.map { b -> b.identity.packageName } })
            .filter { it.isNotBlank() }.distinct().mapNotNull { name -> store.inspector.installed(name)?.let { name to it } }.toMap()
        tasks.value.filter { it.installSucceeded && it.status !in setOf(TaskStatus.INSTALLED, TaskStatus.CANCELLED) }.forEach { task ->
            val current = _installed.value[task.packageName] ?: store.inspector.installed(task.packageName)
            if (current != null && task.matchesInstalled(current)) {
                store.taskApp(task)?.let { store.library.bind(it, current, task.asset.name, task.releaseTag, task.prerelease) }
                store.tasks.update(task.id) { it.copy(status = TaskStatus.INSTALLED, error = null) }
            }
        }
    }

    fun refresh(id: String, force: Boolean = false) { store.findApp(id)?.let { prepareDetail(it, force) } }
    fun refreshAll() = viewModelScope.launch {
        for (record in store.library.records.value) {
            val linked = record.installChecks && record.bindings.any { it.checks && store.inspector.installed(it.identity.packageName) != null }
            if (!record.subscribed && !linked) continue
            _busy.value += record.app.id
            try { store.library.remember(store.github.checkUpdates(record)) }
            catch (c: CancellationException) { throw c }
            catch (e: Exception) { store.library.mutate(record.app.id) { record.copy(checkError = e.message) }; tell(e.message ?: "更新检查失败"); break }
            finally { _busy.value -= record.app.id }
        }
        refreshInstalled()
    }
    fun download(app: StoreApp, release: AppRelease, asset: ReleaseAsset) {
        if (!agreement.accepted) { tell("请先阅读并同意用户协议"); return }
        store.library.remember(app)
        val task = store.tasks.enqueue(app, release, asset)
        try { DownloadService.start(store) }
        catch (e: Exception) { store.tasks.update(task.id) { it.copy(status = TaskStatus.FAILED, error = "无法启动下载，请在应用前台重试") } }
    }
    fun cancel(task: DownloadTask) = viewModelScope.launch(Dispatchers.IO) {
        store.tasks.update(task.id) { it.copy(status = TaskStatus.CANCELLED, downloaded = 0, error = "已取消下载") }
        if (task.status !in setOf(TaskStatus.DOWNLOADING, TaskStatus.VERIFYING)) DownloadEngine.discard(store.tasks.partial(task.id))
    }
    fun retry(task: DownloadTask) = viewModelScope.launch(Dispatchers.IO) {
        if (!agreement.accepted) { tell("请先同意用户协议"); return@launch }
        if (store.tasks.file(task.id).isFile) {
            try {
                val app = store.taskApp(task) ?: error("来源记录缺失，请重新打开仓库")
                val info = store.inspector.verify(app.copy(packageName = "", certificateSha256 = emptySet()), task.asset, store.tasks.file(task.id))
                store.tasks.update(task.id) { it.copy(status = TaskStatus.READY, inspected = info, packageName = info.packageName,
                    localSha256 = dev.githubstore.download.sha256(store.tasks.file(task.id)), sourceConfirmed = false, error = null) }; return@launch
            } catch (e: Exception) { store.tasks.file(task.id).delete() }
        }
        store.tasks.update(task.id) { it.copy(status = TaskStatus.QUEUED, error = null, sourceConfirmed = false) }
        try { DownloadService.start(store) } catch (_: Exception) { store.tasks.update(task.id) { it.copy(status = TaskStatus.FAILED, error = "请在应用前台重新下载") } }
    }
    fun confirmSource(task: DownloadTask) { store.tasks.update(task.id) { it.copy(sourceConfirmed = true) } }
    fun install(task: DownloadTask) = viewModelScope.launch {
        try { installer.install(task) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            tell(e.message ?: "无法开始安装")
            store.tasks.update(task.id) { if (it.status in setOf(TaskStatus.INSTALLING, TaskStatus.WAITING_USER)) it else it.copy(status = TaskStatus.READY, error = e.message) }
        }
    }
    fun open(app: StoreApp) { if (!store.inspector.launch(app.packageName)) tell("此应用没有可用的 TV 启动入口") }
    fun ignore(app: StoreApp, asset: ReleaseAsset) {
        val key = "${app.id}:${asset.versionCode ?: asset.id}"
        _ignored.value = _ignored.value.toMutableSet().apply { if (!add(key)) remove(key) }
        preferences.edit().putStringSet("ignored", _ignored.value).apply()
    }
    fun clearCache() = viewModelScope.launch(Dispatchers.IO) {
        val size = store.tasks.clearCompletedFiles()
        store.readmes.clearCache()
        store.github.transport.clearCache()
        store.imageLoader.diskCache?.clear()
        store.imageLoader.memoryCache?.clear()
        tell("已清理 ${formatBytes(size)} 下载缓存及图片缓存")
    }
}
