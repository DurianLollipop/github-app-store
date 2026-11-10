package dev.githubstore.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Extract display data only. Never render or execute upstream HTML. */
object ReadmePresentation {
    data class Info(val description: String, val icon: String, val screenshots: List<String>, val url: String)
    private val badge = Regex("shields\\.io|/badges?/|/workflows/|sponsor|donat|weblate|google.play|f-droid", RegexOption.IGNORE_CASE)
    private val logo = Regex("logo|(?:^|[/_\\s-])icon(?:[_.\\s/-]|$)|图标|标志", RegexOption.IGNORE_CASE)
    private val screen = Regex("screen.?shot|preview|截图|预览", RegexOption.IGNORE_CASE)
    fun safeImage(value: String): String {
        val url = value.toHttpUrlOrNull() ?: return ""
        val host = url.host
        return value.takeIf { url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() &&
            host.contains('.') && host !in setOf("localhost", "127.0.0.1") &&
            !host.endsWith(".local") && !host.endsWith(".internal") &&
            !host.all { it.isDigit() || it == '.' } && ':' !in host }.orEmpty()
    }
    fun extract(readme: String, repository: String, htmlUrl: String, path: String): Info {
        val text = readme.take(256 * 1024)
        val canonical = htmlUrl.toHttpUrlOrNull()?.takeIf { it.host == "github.com" && it.encodedPath.startsWith("/$repository/blob/") }
        // The API html_url supplies the actual default ref, including refs that contain slashes.
        val ref = canonical?.encodedPath?.removePrefix("/$repository/blob/")?.removeSuffix("/$path") ?: "HEAD"
        val rawBase = "https://raw.githubusercontent.com/$repository/$ref/".toHttpUrlOrNull()!!
        val directory = path.substringBeforeLast('/', "")
        fun image(value: String): String {
            val normalized = value.trim().removeSurrounding("<", ">").replace("&amp;", "&")
            val resolved = when {
                normalized.startsWith("//") -> "https:$normalized"
                normalized.startsWith("/") -> rawBase.resolve(normalized.removePrefix("/"))?.toString().orEmpty()
                normalized.startsWith("https://github.com/") && "/blob/" in normalized -> normalized.replace("https://github.com/", "https://raw.githubusercontent.com/").replace("/blob/", "/")
                normalized.startsWith("https://") -> normalized
                Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(normalized) -> ""
                else -> rawBase.resolve((if (directory.isEmpty()) "" else "$directory/") + normalized)?.toString().orEmpty()
            }
            return safeImage(resolved)
        }
        val images = mutableListOf<Pair<String, String>>()
        Regex("!\\[([^]]*)]\\(([^\\s)]+)(?:\\s+[^)]*)?\\)").findAll(text).forEach {
            images += it.groupValues[1] to image(it.groupValues[2])
        }
        Regex("<img\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(text).forEach {
            fun attribute(name: String) = Regex("\\b$name\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                .find(it.value)?.groupValues?.get(1).orEmpty()
            images += attribute("alt") to image(attribute("src"))
        }
        val references = Regex("(?m)^\\s*\\[([^]]+)]:\\s*(\\S+)").findAll(text)
            .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        Regex("!\\[([^]]*)]\\[([^]]*)]").findAll(text).forEach {
            val key = it.groupValues[2].ifBlank { it.groupValues[1] }.lowercase()
            references[key]?.let { target -> images += it.groupValues[1] to image(target) }
        }
        val valid = images.filter { it.second.isNotEmpty() && !badge.containsMatchIn(it.first + it.second) }.distinctBy { it.second }
        val icon = valid.firstOrNull { logo.containsMatchIn(it.first + " " + it.second) && !screen.containsMatchIn(it.first + it.second) }?.second.orEmpty()
        val screenshots = valid.filter { screen.containsMatchIn(it.first + it.second) }.map { it.second }.take(10)
        val cleaned = text.replace(Regex("```[\\s\\S]*?```|~~~[\\s\\S]*?~~~"), "")
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("!\\[[^]]*]\\([^)]*\\)|!\\[[^]]*]\\[[^]]*]"), "")
            .replace(Regex("<[^>]+>"), "").replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^\\s*#{1,6}\\s*"), "").replace("**", "").replace("`", "")
            .split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.length > 30 && !badge.containsMatchIn(it) }
            .take(12).joinToString("\n\n").take(12_000)
        return Info(cleaned, icon, screenshots, canonical?.toString().orEmpty())
    }
}
