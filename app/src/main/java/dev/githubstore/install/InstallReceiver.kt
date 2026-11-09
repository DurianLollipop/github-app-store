package dev.githubstore.install

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.githubstore.MainActivity
import dev.githubstore.R
import dev.githubstore.StoreApplication
import dev.githubstore.domain.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow

object InstallConfirmation { val pending = MutableStateFlow<Intent?>(null) }

class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val store = context.applicationContext as StoreApplication
        val id = intent.getStringExtra("taskId") ?: return
        val task = store.tasks.find(id) ?: return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirmation == null) {
                    store.tasks.update(id) { it.copy(status = TaskStatus.FAILED, error = "系统未提供安装确认入口，请重试") }; return
                }
                store.tasks.update(id) { it.copy(status = TaskStatus.WAITING_USER) }
                InstallConfirmation.pending.value = confirmation
                val notifications = context.getSystemService(NotificationManager::class.java)
                notifications.createNotificationChannel(NotificationChannel("install", "安装确认", NotificationManager.IMPORTANCE_DEFAULT))
                val open = Intent(context, MainActivity::class.java).putExtra("confirmation", confirmation).putExtra("openDownloads", true)
                val pending = PendingIntent.getActivity(context, task.sessionId ?: 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                runCatching { notifications.notify(task.sessionId ?: 20, NotificationCompat.Builder(context, "install").setSmallIcon(R.drawable.ic_orbit)
                    .setContentTitle("继续安装 ${task.appName}").setContentText("选择此通知或返回 Orbit 确认安装")
                    .setContentIntent(pending).setAutoCancel(true).build()) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val installed = store.inspector.installed(task.packageName)
                store.tasks.update(id) { it.copy(status = if (installed != null) TaskStatus.INSTALLED else TaskStatus.FAILED,
                    error = if (installed == null) "系统报告成功，但安装状态暂不可见，请重新检查" else null) }
                if (installed != null) store.tasks.file(id).delete()
                context.getSystemService(NotificationManager::class.java).cancel(task.sessionId ?: 20)
            }
            else -> {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
                val message = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "已取消系统安装，可以再次安装"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "设备存储空间不足，请释放空间后重试"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "与已安装应用的包名或签名冲突，无法覆盖"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "此安装包与当前系统或设备不兼容"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "设备策略或系统验证阻止了安装，请检查系统提示"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "系统认为安装包无效，请重新下载"
                    else -> "系统安装失败（$status），请检查系统提示后重试"
                }
                store.tasks.update(id) { it.copy(status = TaskStatus.FAILED, error = message) }
                context.getSystemService(NotificationManager::class.java).cancel(task.sessionId ?: 20)
            }
        }
    }
}
