package dev.githubstore

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.githubstore.domain.DownloadTask
import dev.githubstore.install.InstallConfirmation
import dev.githubstore.ui.StoreRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: StoreViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingTask?.let { if (packageManager.canRequestPackageInstalls()) model.install(it) else model.tell("未授予安装来源权限，可稍后重试") }
        pendingTask = null
    }
    private var pendingTask: DownloadTask? = null
    private val launchConfirmation = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.refreshInstalled() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        consumeIntent(intent)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.RESUMED) {
            InstallConfirmation.pending.collect { confirmation ->
                if (confirmation != null) {
                    InstallConfirmation.pending.value = null
                    runCatching { launchConfirmation.launch(confirmation) }.onFailure { model.tell("无法打开系统确认，请在下载中心再次安装") }
                }
            }
        } }
        setContent { StoreRoot(model,
            onInstall = ::requestInstall,
            onDownloadPermission = { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
            openDownloads = intent.getBooleanExtra("openDownloads", false),
            onExternal = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { model.tell("电视上没有可打开链接的浏览器：$url") } },
            onUninstall = { packageName -> runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))) }
                .onFailure { model.tell("无法打开系统卸载入口，请前往设备设置") } }) }
    }
    override fun onResume() { super.onResume(); model.refreshInstalled() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consumeIntent(intent) }
    @Suppress("DEPRECATION") private fun consumeIntent(intent: Intent) {
        val confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("confirmation", Intent::class.java) else intent.getParcelableExtra("confirmation")
        if (confirmation != null) { InstallConfirmation.pending.value = confirmation; intent.removeExtra("confirmation") }
    }
    private fun requestInstall(task: DownloadTask) {
        if (packageManager.canRequestPackageInstalls()) model.install(task)
        else {
            pendingTask = task
            runCatching { installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                .onFailure { pendingTask = null; model.tell("无法打开来源授权，请在设备设置中允许 Orbit 安装应用") }
        }
    }
}
