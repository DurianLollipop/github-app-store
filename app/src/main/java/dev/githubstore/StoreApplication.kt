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
    lateinit var library: UserLibrary; private set
    lateinit var readmes: dev.githubstore.readme.ReadmeRepository; private set
    lateinit var discovery: DiscoveryRepository
    lateinit var agreement: AgreementStore; private set
    lateinit var inspector: PackageInspector; private set
    val client: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).followSslRedirects(true).build() }
    lateinit var auth: GitHubAuthStore; private set
    var github: GitHubDiscovery = GitHubDiscovery(client)
    val deviceFlow by lazy { GitHubDeviceFlow(client) }
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(client).diskCache { DiskCache.Builder().directory(File(cacheDir, "image_cache"))
            .maxSizeBytes(64L * 1024 * 1024).build() }.components { add(SvgDecoder.Factory()) }.build()
    override fun onCreate() {
        super.onCreate()
        auth = GitHubAuthStore(this)
        github = GitHubDiscovery(client, transport = GitHubTransport(client, auth::token, File(cacheDir, "github-api"), unauthorized = auth::invalidate))
        catalog = CatalogRepository(this, client)
        tasks = TaskRepository(this, catalog.apps.value)
        library = UserLibrary(this)
        val migrations = getSharedPreferences("schema-migrations", 0)
        if (!migrations.getBoolean("tool03", false)) {
            for (task in tasks.tasks.value.filter { it.status == dev.githubstore.domain.TaskStatus.INSTALLED && it.inspected == null }) {
                catalog.find(task.appId)?.let { app ->
                    // A visible legacy record is a hint to re-check, never an automatically trusted binding.
                    library.remember(app.copy(packageName = task.packageName, certificateSha256 = emptySet(), checkedAt = 0,
                        tvStatus = "旧版安装记录 · 待重新检查来源"))
                }
            }
            migrations.edit().putBoolean("tool03", true).apply()
        }
        agreement = AgreementStore(this)
        readmes = dev.githubstore.readme.ReadmeRepository(this) { github }
        discovery = DiscoveryRepository(this, { github }, client) { app -> library.findRepository(app.repository)?.app }
        inspector = PackageInspector(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("catalog-refresh", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CatalogWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun taskApp(task: dev.githubstore.domain.DownloadTask): dev.githubstore.domain.StoreApp? = findApp(task.appId)
        ?: task.repository.takeIf { Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(it) }?.let {
            dev.githubstore.domain.StoreApp(task.appId, task.appName, it, "", "GitHub 项目", "", "", "未声明", 0xFF4569A6, task.appName.take(1))
        }
    fun findApp(id: String) = library.find(id)?.app ?: catalog.find(id)
    fun reconcileRevocations() { /* Third-party tasks retain their source; no curated directory authorization. */ }

}

class CatalogWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = applicationContext as StoreApplication
        if (!store.getSharedPreferences("preferences", 0).getBoolean("automaticChecks", true)) return Result.success()
        var failures = 0
        for (record in store.library.records.value) {
            val linked = record.installChecks && record.bindings.any { it.checks && store.inspector.installed(it.identity.packageName) != null }
            if (!record.subscribed && !linked) continue
            try { store.library.remember(kotlinx.coroutines.withContext(GitHubRequestPolicy(background = true)) { store.github.checkUpdates(record) }) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) { store.library.mutate(record.app.id) { record.copy(checkError = e.message) }; failures++; break }
        }
        return if (failures == 0) Result.success() else Result.retry()
    }
}
