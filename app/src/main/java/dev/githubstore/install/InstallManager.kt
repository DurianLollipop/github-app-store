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
        check(store.agreement.accepted) { "请先阅读并同意用户协议" }
        val app = store.taskApp(task) ?: error("仓库来源记录缺失，请重新检查")
        check(task.sourceConfirmed && task.inspected != null) { "请先确认此安装包的来源与身份" }
        store.tasks.update(task.id) { it.copy(status = TaskStatus.CHECKING_INSTALL, installSucceeded = false, error = null) }
        val remote = try { store.github.asset(app.repository, task.asset.id) }
        catch (c: kotlinx.coroutines.CancellationException) { throw c }
        catch (e: dev.githubstore.data.GitHubApiException) { if (e.status == 404) error("上游安装包已移除，已停止安装") else null }
        catch (_: java.io.IOException) { null } // Complete locally checked files remain usable offline.
        if (remote != null) {
            check(remote.size == task.asset.size && remote.url == task.asset.url &&
                (task.asset.updatedAt.isBlank() || remote.updatedAt == task.asset.updatedAt)) { "上游安装包发生变化，请重新下载" }
            remote.sha256?.let { check(it.equals(task.localSha256, true)) { "上游摘要与本地文件不同，请重新下载" } }
        }
        check(task.sourceConfirmed && task.inspected != null) { "请先确认此安装包的来源与身份" }
        check(store.tasks.find(task.id)?.status == TaskStatus.CHECKING_INSTALL) { "安装检查已取消" }
        val file = store.tasks.file(task.id)
        check(task.localSha256 != null && dev.githubstore.download.sha256(file) == task.localSha256) { "本地文件发生变化，请重新下载检查" }
        val identity = store.inspector.verify(app.copy(packageName = task.inspected.packageName,
            certificateSha256 = task.inspected.certificates), task.asset, file)
        check(identity == task.inspected) { "包身份发生变化，请重新检查" }
        check(store.packageManager.canRequestPackageInstalls()) { "请先允许 Orbit 安装应用" }
        val installer = store.packageManager.packageInstaller
        task.sessionId?.let { runCatching { installer.abandonSession(it) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(identity.packageName); setSize(file.length())
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
