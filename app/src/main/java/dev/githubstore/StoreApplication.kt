package dev.githubstore

import android.app.Application
import androidx.work.*
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import java.io.File
import dev.githubstore.data.*
import dev.githubstore.install.PackageInspector
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class StoreApplication : Application(), ImageLoaderFactory {
    lateinit var catalog: CatalogRepository; private set
    lateinit var tasks: TaskRepository; private set
    lateinit var inspector: PackageInspector; private set
    val client: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).followSslRedirects(true).build() }
    var github: GitHubDiscovery = GitHubDiscovery(client)
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(client).diskCache { DiskCache.Builder().directory(File(cacheDir, "image_cache"))
            .maxSizeBytes(64L * 1024 * 1024).build() }.components { add(SvgDecoder.Factory()) }.build()
    override fun onCreate() {
        super.onCreate()
        catalog = CatalogRepository(this, client)
        tasks = TaskRepository(this)
        inspector = PackageInspector(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("catalog-refresh", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CatalogWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun reconcileRevocations() {
        for (task in tasks.tasks.value.filter { it.active || it.status in setOf(dev.githubstore.domain.TaskStatus.READY,
            dev.githubstore.domain.TaskStatus.WAITING_USER, dev.githubstore.domain.TaskStatus.INSTALLING) }) {
            val app = catalog.find(task.appId)
            val asset = app?.releases?.flatMap { it.assets }?.firstOrNull { it.url == task.asset.url && it.sha256 == task.asset.sha256 }
            if (app == null || asset?.enabled == false) {
                task.sessionId?.let { runCatching { packageManager.packageInstaller.abandonSession(it) } }
                tasks.update(task.id) { it.copy(status = dev.githubstore.domain.TaskStatus.CANCELLED, error = "此安装包已停止分发，已请求停止任务") }
            }
        }
    }
}

class CatalogWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = applicationContext as StoreApplication
        var failures = 0
        try { store.catalog.refreshSnapshot(); store.reconcileRevocations() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { return Result.retry() }
        for (app in store.catalog.apps.value) {
            try { store.catalog.refresh(app.id) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { failures++; break }
        }
        return if (failures == 0) Result.success() else Result.retry()
    }
}
