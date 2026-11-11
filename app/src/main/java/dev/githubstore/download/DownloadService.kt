package dev.githubstore.download

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.githubstore.MainActivity
import dev.githubstore.R
import dev.githubstore.StoreApplication
import dev.githubstore.domain.TaskStatus
import kotlinx.coroutines.*

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store get() = application as StoreApplication
    private lateinit var engine: DownloadEngine
    private var runner: Job? = null
    private var activeId: String? = null
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        engine = DownloadEngine(store.client)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "应用下载", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        ServiceCompat.startForeground(this, 10, notification("准备下载", 0),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (runner?.isActive != true) runner = scope.launch {
            try {
                while (true) {
                    val task = store.tasks.tasks.value.firstOrNull { it.status == TaskStatus.QUEUED } ?: break
                    activeId = task.id
                    val cancellationMonitor = scope.launch {
                        store.tasks.tasks.collect { tasks ->
                            if (tasks.any { it.id == task.id && it.status == TaskStatus.CANCELLED }) engine.cancel()
                        }
                    }
                    try { withContext(Dispatchers.IO) {
                        try {
                            val app = store.taskApp(task) ?: error("来源记录缺失，请重新打开仓库后下载")
                            store.tasks.update(task.id) { if (it.status == TaskStatus.QUEUED) it.copy(status = TaskStatus.DOWNLOADING, error = null) else it }
                            if (store.tasks.find(task.id)?.status != TaskStatus.DOWNLOADING) return@withContext
                            engine.download(task.repository.ifBlank { app.repository }, task.asset, store.tasks.partial(task.id), store.tasks.file(task.id),
                                onProgress = { count ->
                                    if (store.tasks.find(task.id)?.status == TaskStatus.CANCELLED) { engine.cancel(); throw CancellationException("已取消") }
                                    store.tasks.update(task.id) { it.copy(downloaded = count) }
                                    getSystemService(NotificationManager::class.java).notify(10, notification(task.appName, (count * 100 / task.asset.size).toInt()))
                                }, onVerifying = {
                                    if (store.tasks.find(task.id)?.status == TaskStatus.CANCELLED) throw CancellationException("已取消")
                                    store.tasks.update(task.id) { if (it.status == TaskStatus.DOWNLOADING) it.copy(status = TaskStatus.VERIFYING) else it }
                                })
                            if (store.tasks.find(task.id)?.status == TaskStatus.CANCELLED) store.tasks.file(task.id).delete()
                            else {
                                // Unknown repository identity is read from the APK, never invented from README.
                                val inspected = store.inspector.verify(app.copy(packageName = "", certificateSha256 = if (app.id == "orbit-store") app.certificateSha256 else emptySet()), task.asset, store.tasks.file(task.id))
                                store.library.remember(app.copy(releases = app.releases.map { release -> release.copy(assets = release.assets.map { asset ->
                                    if (asset.id == task.asset.id && asset.url == task.asset.url) asset.copy(versionCode = inspected.versionCode) else asset
                                }) }))
                                store.tasks.update(task.id) { it.copy(status = TaskStatus.READY, packageName = inspected.packageName,
                                    repository = task.repository.ifBlank { app.repository }, inspected = inspected,
                                    localSha256 = sha256(store.tasks.file(task.id)), sourceConfirmed = false) }
                            }
                        } catch (cancelled: CancellationException) {
                            if (store.tasks.find(task.id)?.status != TaskStatus.CANCELLED)
                                store.tasks.update(task.id) { it.copy(status = TaskStatus.INTERRUPTED,
                                    downloaded = DownloadEngine.persistedBytes(store.tasks.partial(task.id)).coerceAtMost(task.asset.size),
                                    error = "下载被系统中断，已保留进度，可继续下载") }
                            if (!currentCoroutineContext().isActive) throw cancelled
                        } catch (error: Exception) {
                            if (store.tasks.find(task.id)?.status != TaskStatus.CANCELLED)
                                store.tasks.update(task.id) { it.copy(status = TaskStatus.FAILED,
                                    downloaded = DownloadEngine.persistedBytes(store.tasks.partial(task.id)).coerceAtMost(task.asset.size), error = when (error) {
                                    is java.net.UnknownHostException -> "无法连接 GitHub，请检查设备网络后重试"
                                    is java.net.SocketTimeoutException -> "下载连接超时，请检查网络后重试"
                                    is javax.net.ssl.SSLException -> "无法建立安全连接，请检查设备时间与网络"
                                    else -> error.message ?: "下载失败，请检查网络"
                                }) }
                        }
                    } } finally {
                        cancellationMonitor.cancel()
                        if (store.tasks.find(task.id)?.status == TaskStatus.CANCELLED) {
                            withContext(NonCancellable + Dispatchers.IO) { DownloadEngine.discard(store.tasks.partial(task.id)) }
                        }
                    }
                }
            } finally { activeId = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(latestStartId) }
        }
        return START_NOT_STICKY
    }

    private fun notification(title: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("openDownloads", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_orbit)
            .setContentTitle(title).setContentText("在下载中心查看进度").setContentIntent(open)
            .setProgress(100, percent, percent == 0).setOngoing(true).setOnlyAlertOnce(true).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { engine.cancel(); scope.cancel(); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) {
        activeId?.let { id -> store.tasks.update(id) { it.copy(status = TaskStatus.INTERRUPTED, error = "系统已暂停长时间下载，请重试") } }
        engine.cancel(); stopSelf()
    }
    companion object {
        private const val CHANNEL = "downloads"
        fun start(context: Context) = androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }
}
