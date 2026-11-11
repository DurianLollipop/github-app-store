package dev.githubstore.readme

import dev.githubstore.data.ReadmePresentation
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.ext.gfm.tables.TablesExtension
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URI

/** Only data reaches Compose. HTML is parsed, never executed. */
data class ReadmeFile(val path: String, val ref: String, val text: String, val url: String, val fetchedAt: Long,
    val sha: String = "", val cached: Boolean = false)
data class LanguageFile(val path: String, val label: String)
data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val href: String = "")
data class DocumentBlock(val kind: String, val runs: List<Run> = emptyList(), val url: String = "", val anchor: String = "",
    val group: Int = 0, val level: Int = 0) { val text get() = runs.joinToString("") { it.text } }

object MarkdownDocument {
    const val MAX_BYTES = 1024 * 1024
    private val extensions = listOf(TablesExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder().extensions(extensions).sanitizeUrls(true).build()
    private fun render(text: String): String {
        require(text.toByteArray().size <= MAX_BYTES) { "README 超过 1 MiB，请查看原文" }
        val root = parser.parse(text)
        val stack = java.util.ArrayDeque<Pair<org.commonmark.node.Node, Int>>()
        stack.add(root to 0)
        var count = 0
        while (!stack.isEmpty()) {
            val (node, depth) = stack.removeLast()
            require(depth < 96 && ++count <= 16_000) { "README 结构过于复杂，请查看原文" }
            var child = node.firstChild
            while (child != null) { stack.add(child to depth + 1); child = child.next }
        }
        return renderer.render(root)
    }
    fun resolve(repository: String, file: ReadmeFile, value: String, image: Boolean = false): String {
        if (value.startsWith('#')) return value
        val base = "https://github.com/$repository/blob/${file.ref}/${file.path}"
        val url = runCatching { URI(base).resolve(value.replace(" ", "%20")).toString() }.getOrDefault("")
        if (image) {
            val raw = if (url.startsWith("https://github.com/") && "/blob/" in url) url.replace("https://github.com/", "https://raw.githubusercontent.com/").replace("/blob/", "/") else url
            return ReadmePresentation.safeImage(raw)
        }
        return ReadmePresentation.safeImage(url)
    }
    fun languages(markdown: String): List<Pair<String, String>> {
        val dom = Jsoup.parseBodyFragment(render(markdown))
        return dom.select("a[href]").take(100).map { it.text() to it.attr("href") }
    }
    fun languageLabel(path: String): String? {
        val name = path.substringAfterLast('/')
        if (!Regex("(?i)^readme(?:[._-][a-zA-Z0-9_-]+)?\\.(md|markdown)$").matches(name)) return null
        val tag = name.substringBeforeLast('.').replaceFirst(Regex("^readme", RegexOption.IGNORE_CASE), "").trim('.', '_', '-').lowercase()
        if (tag.isNotEmpty() && !Regex("[a-z]{2,3}(?:[-_][a-z0-9]{2,8})*").matches(tag)) return null
        return when {
            tag.isEmpty() -> "仓库默认"
            tag in setOf("zh", "zh-cn", "zh_cn", "cn", "zh-hans") -> "简体中文"
            tag in setOf("zh-tw", "zh_tw", "zh-hant") -> "繁體中文"
            tag == "en" || tag.startsWith("en-") -> "English"
            tag == "ja" || tag == "jp" -> "日本語"
            tag == "ko" -> "한국어"
            tag == "ru" -> "Русский"
            tag == "de" -> "Deutsch"
            tag == "fr" -> "Français"
            tag == "es" -> "Español"
            tag == "pt" || tag == "pt-br" -> "Português"
            else -> name
        }
    }
    fun parse(repository: String, file: ReadmeFile): List<DocumentBlock> {
        require(file.text.toByteArray().size <= MAX_BYTES) { "README 超过 1 MiB，请查看原文" }
        val dom = Jsoup.parseBodyFragment(render(file.text))
        dom.select("script,style,iframe,object,embed").remove()
        require(dom.getAllElements().size <= 16_000) { "README 结构过于复杂，请查看原文" }
        val blocks = mutableListOf<DocumentBlock>()
        val anchors = mutableMapOf<String, Int>()
        var nextGroup = 1
        fun inline(e: org.jsoup.nodes.Node, bold: Boolean = false, italic: Boolean = false, code: Boolean = false, href: String = "", depth: Int = 0): List<Run> {
            require(depth < 96) { "README 嵌套过深，请查看原文" }
            if (e is TextNode) return listOf(Run(e.wholeText, bold, italic, code, href))
            if (e !is Element) return emptyList()
            if (e.tagName() in setOf("img", "source")) return emptyList()
            if (e.tagName() == "br") return listOf(Run("\n"))
            return e.childNodes().flatMap { inline(it, bold || e.tagName() in setOf("b", "strong"), italic || e.tagName() in setOf("em", "i"), code || e.tagName() == "code",
                if (e.tagName() == "a") resolve(repository, file, e.attr("href")) else href, depth + 1) }
        }
        fun addText(kind: String, runs: List<Run>, group: Int, level: Int = 0, anchor: String = "") {
            // Split long paragraphs without losing link/style spans, so every part is reachable by D-pad.
            var pending = mutableListOf<Run>(); var count = 0; var first = true
            fun emit() { if (pending.any { it.text.isNotBlank() }) { blocks += DocumentBlock(kind, pending.toList(), anchor = if (first) anchor else "", group = group, level = level); first = false }; pending = mutableListOf(); count = 0 }
            for (run in runs) {
                val text = run.text
                var offset = 0
                while (offset < text.length) {
                    var take = minOf(text.length - offset, 360 - count)
                    if (take > 0 && offset + take < text.length && Character.isHighSurrogate(text[offset + take - 1])) take--
                    if (take == 0) { emit(); continue }
                    pending += run.copy(text = text.substring(offset, offset + take)); count += take; offset += take
                    if (count >= 359) emit()
                }
            }
            emit()
        }
        fun walk(e: Element, group: Int = 0, depth: Int = 0) {
            require(depth < 96) { "README 嵌套过深，请查看原文" }
            when (e.tagName()) {
                "details" -> {
                    val id = if (group == 0) nextGroup++ else group
                    blocks += DocumentBlock(if (group == 0) "details" else "text", listOf(Run(e.selectFirst("summary")?.text() ?: "展开内容")), group = id)
                    e.children().filter { it.tagName() != "summary" }.forEach { walk(it, id, depth + 1) }
                }
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val slug = e.text().lowercase().replace(Regex("[^\\p{L}\\p{N} _-]"), "").replace(' ', '-')
                    val index = anchors.getOrDefault(slug, 0); anchors[slug] = index + 1
                    addText("heading", inline(e), group, e.tagName().drop(1).toInt(), if (index == 0) slug else "$slug-$index")
                }
                "pre" -> e.wholeText().lines().forEach { line ->
                    line.ifEmpty { " " }.chunked(1000).forEach { chunk -> blocks += DocumentBlock("code", listOf(Run(chunk, code = true)), group = group) }
                }
                "table" -> {
                    e.select("tr").forEach { row -> blocks += DocumentBlock("table", listOf(Run(row.children().joinToString("  │  ") { it.text() }, code = true)), group = group) }
                }
                "hr" -> blocks += DocumentBlock("rule", group = group)
                "img" -> resolve(repository, file, e.attr("src"), true).takeIf { it.isNotEmpty() }?.let { blocks += DocumentBlock("image", listOf(Run(e.attr("alt"))), it, group = group) }
                "picture" -> e.selectFirst("img")?.let { walk(it, group, depth + 1) }
                "p", "li", "blockquote", "summary" -> {
                    if (e.children().any { it.tagName() in setOf("p", "ul", "ol", "pre", "blockquote") }) {
                        val direct = e.childNodes().filterNot { it is Element && it.tagName() in setOf("p", "ul", "ol", "pre", "blockquote") }.flatMap { inline(it) }
                        addText("text", direct, group)
                        for (child in e.children().filter { it.tagName() in setOf("p", "ul", "ol", "pre", "blockquote") }) walk(child, group, depth + 1)
                    } else {
                        val prefix = if (e.tagName() == "li") listOf(Run(if (e.parent()?.tagName() == "ol") "${e.elementSiblingIndex() + 1}. " else "• ")) else emptyList()
                        addText(if (e.tagName() == "blockquote") "quote" else "text", prefix + inline(e), group)
                        e.select("img").forEach { walk(it, group, depth + 1) }
                    }
                }
                else -> {
                    if (e.children().isEmpty()) addText("text", inline(e), group)
                    else e.childNodes().forEach { child -> if (child is Element) walk(child, group, depth + 1) else if (child is TextNode && child.text().isNotBlank()) addText("text", inline(child), group) }
                }
            }
        }
        walk(dom.body())
        require(blocks.size <= 12_000) { "README 内容过于复杂，请查看原文" }
        return blocks
    }
}
