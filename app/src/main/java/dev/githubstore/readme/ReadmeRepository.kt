package dev.githubstore.readme

import android.content.Context
import dev.githubstore.data.*
import dev.githubstore.domain.StoreApp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class ReadmeRepository(private val context: Context, private val github: () -> GitHubDiscovery) {
    private val dir = File(context.cacheDir, "readme").apply { mkdirs() }
    private val choices = context.getSharedPreferences("readme-language", 0)
    private val parsed = object : LinkedHashMap<String, List<DocumentBlock>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<DocumentBlock>>?) = size > 8
    }
    private val lock = Mutex()
    private fun key(app: StoreApp, path: String?) = MessageDigest.getInstance("SHA-256")
        .digest("${app.githubId}:${app.repository}:${app.defaultBranch}:${path ?: "@default"}".toByteArray()).joinToString("") { "%02x".format(it) }
    fun preferred(app: StoreApp) = choices.getString(app.id, null)
    suspend fun load(app: StoreApp, path: String? = null, force: Boolean = false): ReadmeFile = withContext(Dispatchers.IO) {
        val disk = AtomicJson(File(dir, "${key(app, path)}.json"))
        val cached = runCatching { disk.read()?.let { j -> JSONObject(j).let { ReadmeFile(it.getString("path"), it.getString("ref"), it.getString("text"), it.getString("url"), it.getLong("fetchedAt"), it.optString("sha"), true) } } }.getOrNull()
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAt < 24 * 3600_000L) return@withContext cached
        try {
            val j = withContext(dev.githubstore.data.GitHubRequestPolicy(revalidate = force)) { github().contents(app, path) }
            check(j.optString("encoding") == "base64") { "README 太大或格式不支持，请查看原文" }
            val content = j.getString("content")
            check(content.length <= MarkdownDocument.MAX_BYTES * 1.5) { "README 超过 1 MiB，请查看原文" }
            val bytes = Base64.getMimeDecoder().decode(content)
            check(bytes.size <= MarkdownDocument.MAX_BYTES) { "README 超过 1 MiB，请查看原文" }
            val result = ReadmeFile(j.getString("path"), app.defaultBranch, bytes.toString(Charsets.UTF_8), j.getString("html_url"), System.currentTimeMillis(), j.optString("sha"))
            disk.write(JSONObject().put("path", result.path).put("ref", result.ref).put("text", result.text).put("url", result.url).put("fetchedAt", result.fetchedAt).put("sha", result.sha).toString())
            // Cache is bounded independently from persistent language choices and user records.
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
            result
        } catch (c: CancellationException) { throw c }
        catch (e: Exception) { if (e is GitHubApiException && e.status == 404) throw e; cached ?: throw e }
    }
    suspend fun blocks(app: StoreApp, file: ReadmeFile): List<DocumentBlock> = withContext(Dispatchers.Default) {
        val key = "${app.id}:${file.ref}:${file.path}:${file.sha.ifBlank { file.text.hashCode().toString() }}"
        lock.withLock { synchronized(parsed) { parsed[key] ?: MarkdownDocument.parse(app.repository, file).also { parsed[key] = it } } }
    }
    suspend fun languages(app: StoreApp, current: ReadmeFile): List<LanguageFile> = withContext(Dispatchers.IO) {
        val manifest = AtomicJson(File(dir, "languages-${key(app, null)}.json"))
        val previous = runCatching { manifest.read()?.let(::JSONObject) }.getOrNull()
        if (previous != null && System.currentTimeMillis() - previous.optLong("time") < 6 * 3600_000L) {
            return@withContext previous.getJSONArray("files").objects().map { LanguageFile(it.getString("path"), it.getString("label")) }
        }
        val result = linkedMapOf(current.path to LanguageFile(current.path, MarkdownDocument.languageLabel(current.path) ?: current.path))
        for (directory in setOf("", current.path.substringBeforeLast('/', ""))) {
            try { github().directory(app, directory).forEach { path -> MarkdownDocument.languageLabel(path)?.let { result[path] = LanguageFile(path, it) } } }
            catch (c: CancellationException) { throw c }
            catch (_: Exception) { /* Keep confirmed files when listing is rate limited. */ }
        }
        val prefix = "https://github.com/${app.repository}/blob/${current.ref}/"
        for ((label, link) in MarkdownDocument.languages(current.text).take(40)) {
            val resolved = MarkdownDocument.resolve(app.repository, current, link)
            if (!resolved.startsWith(prefix)) continue
            val path = java.net.URLDecoder.decode(resolved.removePrefix(prefix).substringBefore('#'), "UTF-8")
            val language = MarkdownDocument.languageLabel(path) ?: continue
            if (language == "仓库默认" && !Regex("(?i)english|中文|简体|繁體|繁体|日本語|한국|français|deutsch|español|portugu|русск").containsMatchIn(label)) continue
            if (path !in result && result.size < 10) {
                try {
                    val j = github().contents(app, path)
                    if (j.optString("type", "file") == "file") result[path] = LanguageFile(path,
                        if (language == "仓库默认" && label.length in 1..32) label else language)
                } catch (c: CancellationException) { throw c }
                catch (_: Exception) { }
            }
        }
        val files = result.values.toList()
        manifest.write(JSONObject().put("time", System.currentTimeMillis()).put("files", org.json.JSONArray(files.map { JSONObject().put("path", it.path).put("label", it.label) })).toString())
        files
    }
    fun select(app: StoreApp, file: ReadmeFile) { choices.edit().putString(app.id, file.path).apply() }
    fun forget(app: StoreApp) { choices.edit().remove(app.id).apply() }
    fun clearCache() { dir.listFiles()?.forEach { it.delete() }; synchronized(parsed) { parsed.clear() } }
}
