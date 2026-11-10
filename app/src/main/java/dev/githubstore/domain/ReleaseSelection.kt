package dev.githubstore.domain

import java.time.Instant

/** Explicit historical choices survive refresh; otherwise choose the newest stable release. */
fun selectedRelease(releases: List<AppRelease>, selectedTag: String?): AppRelease? {
    releases.firstOrNull { it.tag == selectedTag }?.let { return it }
    val stable = releases.filterNot { it.prerelease }.ifEmpty { releases }
    return stable.maxByOrNull { runCatching { Instant.parse(it.publishedAt).toEpochMilli() }.getOrDefault(0) }
}
