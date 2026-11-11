package dev.githubstore.domain

import dev.githubstore.data.*
import kotlin.math.ln
import java.time.Instant

/** Deterministic rules, not a learned profile or a safety rating. */
object Recommendation {
    fun score(item: DiscoveryItem, records: List<UserRepository>, now: Long): Double {
        val app = item.app
        val tv = if (DiscoveryRules.tv(app)) 1.0 else .35
        val metadata = (if (app.releases.any { it.assets.isNotEmpty() }) .5 else 0.0) + (if (app.description.isNotBlank()) .25 else 0.0) + (if (app.iconUrl.isNotBlank()) .25 else 0.0)
        val latest = selectedRelease(app.releases, null)
        val age = latest?.publishedAt?.let { runCatching { (now - Instant.parse(it).toEpochMilli()).coerceAtLeast(0) / 86400_000.0 }.getOrNull() }
        val maintenance = if (app.archived) 0.0 else age?.let { 1.0 / (1 + it / 365) } ?: .5
        val interests = records.filter { it.favorite || it.subscribed }.map { DiscoveryRules.category(it.app) }.toSet()
        val affinity = if (interests.isEmpty()) .5 else if (DiscoveryRules.category(app) in interests) 1.0 else .25
        val popularity = (ln(1.0 + app.stars.coerceAtLeast(0)) / ln(100_001.0)).coerceIn(0.0, 1.0)
        return tv * 30 + metadata * 25 + maintenance * 20 + affinity * 15 + popularity * 10
    }
    fun rank(items: List<DiscoveryItem>, records: List<UserRepository>, now: Long): List<DiscoveryItem> {
        val hidden = records.filter { it.hidden }.map { it.app.id }.toSet()
        val pending = items.distinctBy { it.app.id }.filterNot { it.app.id in hidden }
            .sortedWith(compareByDescending<DiscoveryItem> { score(it, records, now) }.thenBy { it.app.id }).toMutableList()
        val result = mutableListOf<DiscoveryItem>()
        while (pending.isNotEmpty()) {
            val row = result.takeLast(result.size % 6)
            val chosen = pending.firstOrNull { item -> row.count { it.app.category == item.app.category } < 2 && row.none { it.app.repository.substringBefore('/') == item.app.repository.substringBefore('/') } } ?: pending.first()
            result += chosen; pending.remove(chosen)
        }
        return result
    }
}
