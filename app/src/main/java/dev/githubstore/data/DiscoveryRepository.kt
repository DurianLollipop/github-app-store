package dev.githubstore.data

import android.content.Context
import dev.githubstore.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.atomic.AtomicInteger

class RequestBudget(limit: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestBudget>
    private val left = AtomicInteger(limit)
    fun consume() { check(left.getAndDecrement() > 0) { "已达到本轮请求预算，稍后继续补全" } }
}
enum class DiscoveryMode(val label: String) { PERSONAL("为你发现"), PLATFORM("本平台热门"), GLOBAL("GitHub 热门项目"), RELEASES("最近发布") }
enum class HotSort(val label: String) { GROWTH("近期新增 Star"), TOTAL("总 Star") }
enum class HotPeriod(val label: String, val query: String) { DAY("今日", "daily"), WEEK("本周", "weekly"), MONTH("本月", "monthly") }
data class DiscoveryItem(val app: StoreApp, val growth: Long? = null, val reason: String = "", val source: String = "", val window: String = "")
data class DiscoverySnapshot(val items: List<DiscoveryItem> = emptyList(), val fetchedAt: Long = 0, val error: String? = null)

object DiscoveryRules {
    fun category(app: StoreApp): String {
        val text = (app.topics.joinToString(" ") + " " + app.tagline + " " + app.name).lowercase()
        return when {
            Regex("video|media|music|player|jellyfin|iptv|视频|音乐").containsMatchIn(text) -> "影音娱乐"
            Regex("file|transfer|send|browser|文件|浏览器").containsMatchIn(text) -> "实用工具"
            Regex("note|todo|office|文档|笔记").containsMatchIn(text) -> "效率工具"
            else -> "其他"
        }
    }
    fun tv(app: StoreApp) = app.topics.any { it in setOf("android-tv", "androidtv", "google-tv", "leanback") } || Regex("android.?tv|google.?tv", RegexOption.IGNORE_CASE).containsMatchIn(app.tagline)
    fun eligible(app: StoreApp) = !app.archived && app.license !in setOf("未声明", "NOASSERTION", "") &&
        app.releases.any { it.assets.isNotEmpty() } && !Regex("(?:^|[- ])sdk(?:$|[- ])|awesome-|tutorial|library for|教程|资源合集", RegexOption.IGNORE_CASE).containsMatchIn(app.name + " " + app.tagline)
    fun sort(items: List<DiscoveryItem>, sort: HotSort): List<DiscoveryItem> = items.distinctBy { it.app.id }
        .filter { sort == HotSort.TOTAL || it.growth != null }
        .sortedWith(compareByDescending<DiscoveryItem> { if (sort == HotSort.TOTAL) it.app.stars else it.growth ?: -1 }.thenByDescending { it.app.stars }.thenBy { it.app.id })
    /** Source calendar days, not a fabricated rolling UTC window or net count delta. */
    fun sourceDate(history: JSONArray, now: Instant): LocalDate? {
        val start = history.objects().maxOfOrNull { it.optLong("week") } ?: return null
        val elapsed = java.time.Duration.between(Instant.ofEpochSecond(start), now).toDays()
        if (elapsed !in 0..6) return null
        // Week timestamps need not be UTC midnight; day 0 is Sunday in the source calendar.
        return Instant.ofEpochSecond(start).atZone(ZoneOffset.UTC).toLocalDate()
            .with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).plusDays(elapsed)
    }
    fun periodStart(period: HotPeriod, today: LocalDate) = when (period) {
        HotPeriod.DAY -> today; HotPeriod.WEEK -> today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)); HotPeriod.MONTH -> today.withDayOfMonth(1)
    }
    fun aggregate(history: JSONArray, period: HotPeriod, today: LocalDate): Long? {
        val start = periodStart(period, today)
        var total = 0L; val covered = mutableSetOf<LocalDate>()
        for (row in history.objects()) {
            val date = Instant.ofEpochSecond(row.getLong("week")).atZone(ZoneOffset.UTC).toLocalDate().with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            val days = row.optJSONArray("days") ?: continue
            for (i in 0 until minOf(days.length(), 7)) {
                val day = date.plusDays(i.toLong())
                if (day < start || day > today || days.isNull(i)) continue
                total += days.getLong(i); covered += day
            }
        }
        return total.takeIf { covered.size.toLong() == java.time.temporal.ChronoUnit.DAYS.between(start, today) + 1 }
    }
}

open class DiscoveryRepository(context: Context, private val github: () -> GitHubDiscovery, private val client: OkHttpClient, private val cachedApp: (StoreApp) -> StoreApp? = { null }) {
    private val dir = File(context.filesDir, "discovery-v1").apply { mkdirs() }
    private val lock = Mutex()
    private val trendCooldown = AtomicJson(File(context.filesDir, "trending-cooldown.json"))
    private var trendBlockedUntil = runCatching { trendCooldown.read()?.let(::JSONObject)?.optLong("until") ?: 0L }.getOrDefault(0L)
    private var trendBackoff = 60_000L
    private var budgetStart = 0L
    private var budget = RequestBudget(12)
    private val attempts = mutableMapOf<String, Long>()
    private fun key(mode: DiscoveryMode, sort: HotSort, period: HotPeriod) = "${mode.name}-${sort.name}-${period.name}"
    private fun window(period: HotPeriod, now: LocalDate = LocalDate.now()) = when (period) {
        HotPeriod.DAY -> now.toString(); HotPeriod.WEEK -> now.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).toString(); HotPeriod.MONTH -> now.withDayOfMonth(1).toString()
    }
    open fun cached(mode: DiscoveryMode, sort: HotSort, period: HotPeriod): DiscoverySnapshot = runCatching {
        val j = JSONObject(AtomicJson(File(dir, "${key(mode, sort, period)}.json")).read() ?: return DiscoverySnapshot())
        if (sort == HotSort.GROWTH && j.optString("window") != window(period)) return DiscoverySnapshot()
        DiscoverySnapshot(j.getJSONArray("items").objects().map { DiscoveryItem(JsonCodec.readApp(it.getJSONObject("app")),
            if (it.has("growth") && !it.isNull("growth")) it.getLong("growth") else null, it.optString("reason"), it.optString("source"), it.optString("window")) }, j.getLong("fetchedAt"))
    }.getOrDefault(DiscoverySnapshot())
    open suspend fun load(mode: DiscoveryMode, sort: HotSort, period: HotPeriod, force: Boolean = false, onPartial: (DiscoverySnapshot) -> Unit = {}): DiscoverySnapshot = lock.withLock {
        val previous = cached(mode, sort, period)
        val now = System.currentTimeMillis()
        val k = key(mode, sort, period)
        if (!force && previous.items.isNotEmpty() && now - previous.fetchedAt < 6 * 3600_000L) return@withLock previous
        if (now - (attempts[k] ?: 0) < 60_000L) return@withLock previous.copy(error = "请稍后刷新，已保留当前结果")
        attempts[k] = now
        if (now - budgetStart > 60_000L) { budgetStart = now; budget = RequestBudget(12) }
        var warning: String? = null
        val items = mutableListOf<DiscoveryItem>()
        try { withContext(Dispatchers.IO + budget + GitHubRequestPolicy(background = true)) {
            if (mode == DiscoveryMode.GLOBAL && sort == HotSort.TOTAL) {
                github().query("stars:>1 archived:false fork:false").forEach { items += DiscoveryItem(it, reason = "按总 Star 排序", source = "GitHub Search") }
            } else {
                val trends = if (mode == DiscoveryMode.GLOBAL && sort == HotSort.GROWTH) {
                    try { trending(period) } catch (c: CancellationException) { throw c } catch (e: Exception) { warning = "趋势数据暂不可用：${e.message}"; emptyList() }
                } else emptyList()
                if (mode == DiscoveryMode.GLOBAL) items += trends
                else {
                    val candidates = (github().query("topic:android-tv archived:false fork:false", if (mode == DiscoveryMode.RELEASES) "updated" else "stars") + trends.map { it.app }).distinctBy { it.id }.take(20)
                    for (candidate in candidates.take(5)) {
                        try {
                            val app = (cachedApp(candidate)?.takeIf { System.currentTimeMillis() - it.checkedAt < 24 * 3600_000L } ?: github().details(candidate)).let { it.copy(category = DiscoveryRules.category(it), tvStatus = if (DiscoveryRules.tv(it)) "作者标记与 Android TV 相关 · 未经真机验证" else "普通 Android 项目 · TV 兼容性未知") }
                            if (!DiscoveryRules.eligible(app)) continue
                            var growth: Long? = null
                            var calendarWindow = ""
                            if (mode == DiscoveryMode.PLATFORM && sort == HotSort.GROWTH) {
                                val history = github().starHistory(app)
                                val sourceDay = DiscoveryRules.sourceDate(history, Instant.now())
                                if (sourceDay != null) {
                                    growth = DiscoveryRules.aggregate(history, period, sourceDay)
                                    calendarWindow = "${DiscoveryRules.periodStart(period, sourceDay)}～$sourceDay（来源日历）"
                                }
                            }
                            items += DiscoveryItem(app, growth, if (DiscoveryRules.tv(app)) "与 Android TV 相关" else "TV 兼容性未知",
                                if (growth != null) "GitHub Star History · 来源日历聚合" else "GitHub Releases", if (growth != null) calendarWindow else "")
                        } catch (c: CancellationException) { throw c }
                        catch (e: Exception) { warning = e.message; if (e.message?.contains("预算") == true || e.message?.contains("限流") == true) break }
                    }
                }
            }
            if (mode == DiscoveryMode.GLOBAL) {
                val sorted = DiscoveryRules.sort(items, sort)
                items.clear(); items.addAll(sorted)
                currentCoroutineContext().ensureActive()
                val initial = DiscoverySnapshot(items.toList(), now, warning)
                if (items.isNotEmpty()) persist(k, period, initial)
                onPartial(initial)

            }
        } } catch (c: CancellationException) { throw c }
        catch (e: Exception) { warning = e.message ?: "无法连接 GitHub" }
        val hot = mode in setOf(DiscoveryMode.GLOBAL, DiscoveryMode.PLATFORM)
        val ordered = when {
            hot -> DiscoveryRules.sort(items, sort)
            mode == DiscoveryMode.RELEASES -> items.sortedByDescending { selectedRelease(it.app.releases, null)?.publishedAt }
            else -> items.sortedWith(compareByDescending<DiscoveryItem> { DiscoveryRules.tv(it.app) }.thenByDescending { it.app.stars })
        }
        if (ordered.isEmpty()) return@withLock previous.copy(error = warning ?: if (sort == HotSort.GROWTH && hot) "近期增长暂不可用；可查看官方趋势或切换总 Star" else "暂未发现符合条件的应用，请使用全站搜索")
        val result = DiscoverySnapshot(ordered.take(200), now, warning)
        persist(k, period, result)
        result
    }
    private fun persist(key: String, period: HotPeriod, result: DiscoverySnapshot) {
        AtomicJson(File(dir, "$key.json")).write(JSONObject().put("fetchedAt", result.fetchedAt).put("window", window(period))
            .put("items", JSONArray(result.items.map { JSONObject().put("app", JsonCodec.app(it.app)).put("growth", it.growth).put("reason", it.reason).put("source", it.source).put("window", it.window) })).toString())
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(6)?.forEach { it.delete() }
    }
    private suspend fun trending(period: HotPeriod): List<DiscoveryItem> = withContext(Dispatchers.IO) {
        check(System.currentTimeMillis() >= trendBlockedUntil) { "GitHub 趋势访问冷却中，请稍后重试；已有缓存仍可浏览" }
        currentCoroutineContext()[RequestBudget]?.consume()
        val request = Request.Builder().url("https://github.com/trending?since=${period.query}").header("User-Agent", "OrbitStore/0.3").build()
        val call = client.newCall(request)
        val hook = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try { call.execute().use { response ->
            if (response.code in setOf(403, 429)) {
                trendBlockedUntil = System.currentTimeMillis() + (response.header("Retry-After")?.toLongOrNull()?.times(1000) ?: trendBackoff)
                trendBackoff = (trendBackoff * 2).coerceAtMost(15 * 60_000)
                trendCooldown.write(JSONObject().put("until", trendBlockedUntil).toString())
            }
            check(response.isSuccessful) { "趋势页面返回 ${response.code}" }
            trendBackoff = 60_000L
            val body = response.body ?: error("趋势页面为空")
            check(body.contentLength() <= 2 * 1024 * 1024) { "趋势页面过大" }
            val html = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (output.size() <= 2 * 1024 * 1024) { val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
                output.toByteArray()
            }
            check(html.size <= 2 * 1024 * 1024) { "趋势页面过大" }
            parseTrending(html.toString(Charsets.UTF_8), period)
        } } finally { hook.dispose() }
    }
    companion object {
        fun parseTrending(html: String, period: HotPeriod): List<DiscoveryItem> = Jsoup.parse(html).select("article.Box-row").take(30).mapNotNull { row ->
            val path = row.selectFirst("h2 a[href]")?.attr("href")?.trim('/') ?: return@mapNotNull null
            if (!Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(path)) return@mapNotNull null
            val total = row.selectFirst("a[href$=/stargazers]")?.text()?.replace(",", "")?.toLongOrNull() ?: 0
            val expected = when (period) { HotPeriod.DAY -> "today"; HotPeriod.WEEK -> "this week"; HotPeriod.MONTH -> "this month" }
            val label = row.select("span").firstOrNull { Regex("[0-9,]+ stars $expected").matches(it.text()) }?.text()
            val count = label?.substringBefore(" stars")?.replace(",", "")?.toLongOrNull()
            val description = row.selectFirst("p")?.text().orEmpty()
            val app = StoreApp("trend-${path.lowercase().replace('/', '-')}", path.substringAfter('/'), path, "", "GitHub 项目", description, description,
                "未声明", 0xFF4569A6, path.substringAfter('/').take(1).uppercase(), iconUrl = "https://github.com/${path.substringBefore('/')}.png?size=128", stars = total)
            DiscoveryItem(app, count, "${period.label}新增 Star", "GitHub Trending", period.label)
        }
    }
}
