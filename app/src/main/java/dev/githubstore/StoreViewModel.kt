package dev.githubstore

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.githubstore.domain.*
import dev.githubstore.download.DownloadService
import dev.githubstore.install.InstallManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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
        store.tasks.update(task.id) { it.copy(status = TaskStatus.CANCELLED, error = "已取消下载") }
    }
    fun retry(task: DownloadTask) {
        if (store.tasks.file(task.id).isFile && task.status == TaskStatus.FAILED) {
            store.tasks.update(task.id) { it.copy(status = TaskStatus.READY, error = null) }; return
        }
        store.tasks.update(task.id) { it.copy(status = TaskStatus.QUEUED, downloaded = 0, error = null) }
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
    fun clearCache() = viewModelScope.launch(Dispatchers.IO) { tell("已清理 ${formatBytes(store.tasks.clearCompletedFiles())} 下载缓存") }
}
