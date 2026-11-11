package dev.githubstore.data

import android.content.Context
import dev.githubstore.domain.*
import dev.githubstore.download.DownloadEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File
import java.util.UUID

class TaskRepository(context: Context, legacyApps: List<StoreApp> = emptyList()) {
    private val disk = AtomicJson(File(context.filesDir, "tasks.json"))
    private val _tasks = MutableStateFlow(disk.read()?.let { raw -> runCatching {
        JSONArray(raw).objects().map(JsonCodec::readTask).map { old ->
            val task = if (old.repository.isBlank()) old.copy(repository = legacyApps.firstOrNull { it.id == old.appId }?.repository.orEmpty(), sourceConfirmed = false) else old
            if (task.status == TaskStatus.CHECKING_INSTALL) task.copy(status = TaskStatus.READY, sourceConfirmed = false, error = "安装检查已中断，请重新确认")
            else if (task.active) task.copy(status = TaskStatus.INTERRUPTED,
                downloaded = DownloadEngine.persistedBytes(File(context.filesDir, "downloads/${task.id}.part")).coerceAtMost(task.asset.size),
                error = "上次下载中断，已保留进度，可继续下载") else task
        }
    }.getOrNull() }.orEmpty())
    val tasks = _tasks.asStateFlow()
    val downloadDir = File(context.filesDir, "downloads").apply { mkdirs() }

    @Synchronized fun enqueue(app: StoreApp, release: AppRelease, asset: ReleaseAsset): DownloadTask {
        _tasks.value.firstOrNull { it.appId == app.id && it.asset.url == asset.url && it.asset.id == asset.id && it.asset.size == asset.size &&
            it.asset.sha256?.lowercase() == asset.sha256?.lowercase() && it.asset.updatedAt == asset.updatedAt && (it.active || it.status in setOf(TaskStatus.READY, TaskStatus.CHECKING_INSTALL, TaskStatus.WAITING_USER, TaskStatus.INSTALLING)) }?.let { return it }
        val task = DownloadTask(UUID.randomUUID().toString(), app.id, app.name, app.packageName, release.tag, asset, repository = app.repository, prerelease = release.prerelease)
        save(_tasks.value + task)
        return task
    }
    @Synchronized fun update(id: String, transform: (DownloadTask) -> DownloadTask) { save(_tasks.value.map { if (it.id == id) transform(it) else it }) }
    @Synchronized fun find(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }
    @Synchronized private fun save(tasks: List<DownloadTask>) {
        disk.write(JSONArray(tasks.map(JsonCodec::task)).toString())
        _tasks.value = tasks
    }
    fun file(id: String) = File(downloadDir, "$id.apk")
    fun partial(id: String) = File(downloadDir, "$id.part")
    fun clearCompletedFiles(): Long {
        val protected = _tasks.value.filter { it.active || it.status in setOf(TaskStatus.READY, TaskStatus.CHECKING_INSTALL, TaskStatus.WAITING_USER, TaskStatus.INSTALLING) }.map { it.id }.toSet()
        return downloadDir.listFiles().orEmpty().filterNot { it.name.substringBefore('.') in protected }.sumOf { file ->
            val size = if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length()
            if (file.deleteRecursively()) size else 0
        }
    }
}
