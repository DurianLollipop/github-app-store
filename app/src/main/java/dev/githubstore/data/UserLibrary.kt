package dev.githubstore.data

import android.content.Context
import dev.githubstore.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Durable user records are independent of the evictable discovery cache. */
data class UserRepository(val app: StoreApp, val favorite: Boolean = false, val subscribed: Boolean = false,
    val installChecks: Boolean = true, val prerelease: Boolean = false, val bindings: List<PackageBinding> = emptyList(),
    val hidden: Boolean = false, val checkError: String? = null, val touchedAt: Long = System.currentTimeMillis())
data class PackageBinding(val identity: InstalledApp, val channel: String = "", val installedTag: String = "", val checks: Boolean = true, val prerelease: Boolean = false)

class UserLibrary(context: Context) {
    private val disk = AtomicJson(File(context.filesDir, "user-library-v1.json"))
    private val _records = MutableStateFlow(runCatching { disk.read()?.let { raw -> JSONArray(raw).objects().map { j ->
        UserRepository(JsonCodec.readApp(j.getJSONObject("app")), j.optBoolean("favorite"), j.optBoolean("subscribed"),
            j.optBoolean("installChecks", true), j.optBoolean("prerelease"),
            j.optJSONArray("bindings")?.objects().orEmpty().map { PackageBinding(JsonCodec.readInstalled(it.getJSONObject("identity")), it.optString("channel"), it.optString("installedTag"), it.optBoolean("checks", true), it.optBoolean("prerelease")) },
            j.optBoolean("hidden"), j.optString("checkError").takeIf { it.isNotBlank() }, j.optLong("touchedAt"))
    } } }.getOrNull().orEmpty())
    val records = _records.asStateFlow()
    private var batch: List<UserRepository>? = null
    private val current get() = batch ?: _records.value
    @Synchronized fun find(id: String) = current.firstOrNull { it.app.id == id }
    @Synchronized fun findRepository(repo: String) = current.firstOrNull { it.app.repository.equals(repo, true) }
    @Synchronized fun remember(app: StoreApp): StoreApp {
        val old = find(app.id) ?: findRepository(app.repository)?.takeIf { app.githubId == 0L || it.app.githubId == 0L || it.app.githubId == app.githubId }
        val merged = if (old != null && app.checkedAt == 0L && old.app.checkedAt > 0) old.app.copy(stars = app.stars.takeIf { it > 0 } ?: old.app.stars)
            else app.copy(id = old?.app?.id ?: app.id, packageName = old?.app?.packageName?.takeIf { it.isNotBlank() } ?: app.packageName)
        mutate(merged.id) { old?.copy(app = merged, checkError = null, touchedAt = System.currentTimeMillis()) ?: UserRepository(merged) }
        return merged
    }
    @Synchronized fun mutate(id: String, transform: (UserRepository?) -> UserRepository) {
        val next = transform(find(id))
        val all = (current.filterNot { it.app.id == id } + next)
        val pinned = all.filter { it.favorite || it.subscribed || it.bindings.isNotEmpty() || it.hidden }
        val recent = all.filterNot { it in pinned }.sortedByDescending { it.touchedAt }.take(200)
        val saved = pinned + recent
        if (batch != null) { batch = saved; return }
        persist(saved)
    }
    private fun persist(saved: List<UserRepository>) {
        disk.write(JSONArray(saved.map { r -> JSONObject().put("app", JsonCodec.app(r.app)).put("favorite", r.favorite)
            .put("subscribed", r.subscribed).put("installChecks", r.installChecks).put("prerelease", r.prerelease)
            .put("hidden", r.hidden).put("checkError", r.checkError).put("touchedAt", r.touchedAt)
            .put("bindings", JSONArray(r.bindings.map { JSONObject().put("identity", JsonCodec.installed(it.identity)).put("channel", it.channel).put("installedTag", it.installedTag).put("checks", it.checks).put("prerelease", it.prerelease) })) }).toString())
        _records.value = saved
    }
    @Synchronized fun rememberAll(apps: List<StoreApp>): List<StoreApp> {
        batch = _records.value
        try { val result = apps.map(::remember); val saved = batch!!; batch = null; persist(saved); return result }
        finally { batch = null }
    }
    fun bind(app: StoreApp, identity: InstalledApp, channel: String, tag: String, prerelease: Boolean = false) = mutate(app.id) { old ->
        val r = old ?: UserRepository(app)
        r.copy(app = app, bindings = r.bindings.filterNot { it.identity.packageName == identity.packageName } + PackageBinding(identity, channel, tag, r.bindings.firstOrNull { it.identity.packageName == identity.packageName }?.checks ?: true, prerelease))
    }
    @Synchronized fun clear() { disk.write("[]"); _records.value = emptyList() }
}
