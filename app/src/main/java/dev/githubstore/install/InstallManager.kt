package dev.githubstore.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dev.githubstore.StoreApplication
import dev.githubstore.domain.DownloadTask
import dev.githubstore.domain.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class InstallManager(private val store: StoreApplication) {
    private val mutex = Mutex()
    suspend fun install(request: DownloadTask) = mutex.withLock { withContext(Dispatchers.IO) {
        val task = store.tasks.find(request.id) ?: error("下载任务已不存在")
        check(task.status in setOf(TaskStatus.READY, TaskStatus.WAITING_USER)) { "系统正在处理此任务，请等待安装结果" }
        check(store.tasks.tasks.value.none { it.id != task.id && it.status in setOf(TaskStatus.INSTALLING, TaskStatus.WAITING_USER) }) { "请先处理另一个应用的系统安装确认" }
        val app = store.catalog.find(task.appId) ?: error("应用已不在目录中")
        check(System.currentTimeMillis() - app.checkedAt in 0..TimeUnit.HOURS.toMillis(24)) { "版本检查已超过 24 小时，请先联网刷新此应用" }
        check(app.releases.flatMap { it.assets }.any { it.enabled && it.url == task.asset.url && it.sha256 == task.asset.sha256 }) { "安装包已撤回或发生变化，请刷新后重试" }
        val file = store.tasks.file(task.id)
        store.inspector.verify(app, task.asset, file)
        check(store.packageManager.canRequestPackageInstalls()) { "请先允许 Orbit 安装应用" }
        val installer = store.packageManager.packageInstaller
        task.sessionId?.let { runCatching { installer.abandonSession(it) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName); setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output) }; session.fsync(output)
                }
                val callback = Intent(store, InstallReceiver::class.java).putExtra("taskId", task.id)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(store, sessionId, callback, flags)
                store.tasks.update(task.id) { it.copy(status = TaskStatus.INSTALLING, sessionId = sessionId, error = null) }
                session.commit(pending.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            store.tasks.update(task.id) { it.copy(status = TaskStatus.FAILED, error = "无法提交系统安装，请重试") }
            throw error
        }
    } }
}
