package dev.githubstore

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.githubstore.domain.*
import dev.githubstore.data.RepositoryPage
import coil.imageLoader
import dev.githubstore.download.DownloadService
import dev.githubstore.download.DownloadEngine
import dev.githubstore.install.InstallManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class GitHubSearchState(val query: String = "", val results: List<StoreApp> = emptyList(),
    val loading: Boolean = false, val loadingMore: Boolean = false, val total: Int = 0,
    val page: Int = 0, val hasMore: Boolean = false, val error: String? = null, val incomplete: Boolean = false)

class StoreViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application as StoreApplication
    val apps = store.catalog.apps
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
    fun submitSearch() { runSearch(_search.value.query, immediate = true) }
    private fun known(app: StoreApp) = apps.value.firstOrNull { it.repository.equals(app.repository, true) } ?: app
    private fun runSearch(query: String, immediate: Boolean) {
        searchJob?.cancel()
        if (query.isBlank()) { _search.value = GitHubSearchState(query); return }
        _search.value = GitHubSearchState(query, loading = true)
        searchJob = viewModelScope.launch {
            if (!immediate) delay(800)
            try {
                val key = "${query.trim()}:1"
                val result = if (!immediate) searchCache[key] ?: store.github.search(query.trim()) else store.github.search(query.trim())
                ensureActive()
                searchCache[key] = result
                _search.value = GitHubSearchState(query, result.apps.map(::known), total = result.total,
                    page = 1, hasMore = result.hasMore, incomplete = result.incomplete)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _search.value = GitHubSearchState(query, error = error.message ?: "无法连接 GitHub，请检查网络后重试") }
        }
    }
    fun moreSearchResults() {
        val state = _search.value
        if (state.loading || state.loadingMore || !state.hasMore) return
        _search.value = state.copy(loadingMore = true, error = null)
        searchJob = viewModelScope.launch {
            try {
                val result = store.github.search(state.query.trim(), state.page + 1)
                ensureActive()
                _search.value = state.copy(results = (state.results + result.apps.map(::known)).distinctBy { it.id },
                    page = state.page + 1, hasMore = result.hasMore, loadingMore = false, total = result.total)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _search.value = state.copy(error = error.message ?: "加载失败，请重试") }
        }
    }
    fun prepareDetail(app: StoreApp, force: Boolean = false) {
        val curated = apps.value.any { it.id == app.id }
        val current = apps.value.firstOrNull { it.id == app.id } ?: _discovered.value.firstOrNull { it.id == app.id } ?: app
        if (!curated) _discovered.value = (_discovered.value.filterNot { it.id == app.id } + current).takeLast(20)
        if (app.id in _busy.value || (!force && current.checkedAt > System.currentTimeMillis() - 5 * 60_000)) return
        _busy.value += app.id
        viewModelScope.launch {
            try {
                val details = store.github.details(current)
                if (curated) store.catalog.acceptDetails(details)
                else _discovered.value = (_discovered.value.filterNot { it.id == app.id } + details).takeLast(20)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { tell(error.message ?: "无法获取仓库详情，请重试") }
            finally { _busy.value -= app.id }
        }
    }

    init { refreshInstalled() }
    fun tell(message: String) { _message.value = message }
    fun clearMessage() { _message.value = null }
    fun refreshInstalled() = viewModelScope.launch(Dispatchers.IO) {
        _installed.value = apps.value.mapNotNull { app -> store.inspector.installed(app.packageName)?.let { app.packageName to it } }.toMap()
        tasks.value.filter { it.status in setOf(TaskStatus.INSTALLING, TaskStatus.WAITING_USER) }.forEach { task ->
            val current = _installed.value[task.packageName]
            if (current != null && task.asset.versionCode != null && current.versionCode == task.asset.versionCode && current.certificates == apps.value.first { it.id == task.appId }.certificateSha256)
                store.tasks.update(task.id) { it.copy(status = TaskStatus.INSTALLED, error = null) }
        }
    }
    fun refresh(id: String, force: Boolean = false) {
        if (id in _busy.value) return
        _busy.value += id
        viewModelScope.launch {
            try { store.catalog.refresh(id, force) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { tell(e.message ?: "网络连接失败，缓存仍可浏览") }
            finally { _busy.value -= id }
        }
    }
    fun refreshAll() = viewModelScope.launch {
        try { store.catalog.refreshSnapshot(); store.reconcileRevocations() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { tell(e.message ?: "目录同步失败，已保留缓存"); return@launch }
        for (app in apps.value) {
            _busy.value += app.id
            try { store.catalog.refresh(app.id, true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { tell(e.message ?: "检查失败，请重试"); break }
            finally { _busy.value -= app.id }
        }
        refreshInstalled()
    }
    fun download(app: StoreApp, release: AppRelease, asset: ReleaseAsset) {
        if (app.certificateSha256.isEmpty()) { tell("此应用尚未完成发布来源审核，请先查看官方发布页。"); return }
        if (!AssetPolicy.validDigest(asset.sha256)) { tell("此安装包缺少文件校验信息，暂不能下载。可查看官方发布页。"); return }
        val task = store.tasks.enqueue(app, release, asset)
        try { DownloadService.start(store) }
        catch (e: Exception) { store.tasks.update(task.id) { it.copy(status = TaskStatus.FAILED, error = "无法启动下载，请在应用前台重试") } }
    }
    fun cancel(task: DownloadTask) = viewModelScope.launch(Dispatchers.IO) {
        store.tasks.update(task.id) { it.copy(status = TaskStatus.CANCELLED, downloaded = 0, error = "已取消下载") }
        if (task.status !in setOf(TaskStatus.DOWNLOADING, TaskStatus.VERIFYING)) DownloadEngine.discard(store.tasks.partial(task.id))
    }
    fun retry(task: DownloadTask) {
        if (store.tasks.file(task.id).isFile && task.status == TaskStatus.FAILED) {
            store.tasks.update(task.id) { it.copy(status = TaskStatus.READY, error = null) }; return
        }
        store.tasks.update(task.id) { it.copy(status = TaskStatus.QUEUED, error = null) }
        try { DownloadService.start(store) } catch (_: Exception) { store.tasks.update(task.id) { it.copy(status = TaskStatus.FAILED, error = "请在应用前台重新下载") } }
    }
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
        store.imageLoader.diskCache?.clear()
        store.imageLoader.memoryCache?.clear()
        tell("已清理 ${formatBytes(size)} 下载缓存及图片缓存")
    }
}
