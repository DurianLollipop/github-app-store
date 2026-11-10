"""Extract display metadata from README text; never execute or render upstream HTML."""
from html import unescape
from html.parser import HTMLParser
import ipaddress
import posixpath
import re
from urllib.parse import quote, unquote, urljoin, urlsplit, urlunsplit

CONTENT_FIELDS = {"tagline", "description", "iconUrl", "screenshots", "mediaSource", "readmeUrl"}
BAD_IMAGE = re.compile(r"shields\.io|/badge[s/-]|badge\.|svg-badge|/workflows/|/sponsors?/|opencollective|donate|donation|google.play|store-icons|fdroid.gitlab|github-badge|weblate|repology|transifex", re.I)
ICON = re.compile(r"logo|(?:^|[/_\s-])icon(?:[_.\s/-]|$)|图标|标志", re.I)
SCREEN = re.compile(r"screen.?shot|preview|截图|预览", re.I)
EXCLUDED_SECTION = re.compile(r"sponsor|donat|contribut|build|install|download|license|translat|架构|依赖|赞助|捐赠|构建|下载|安装|贡献|许可|翻译", re.I)


def https_url(value):
    """Reject local addresses and non-HTTPS media, including encoded control characters."""
    value = unescape(value.strip())
    if re.search(r"[\x00-\x20\x7f]", value) or re.search(r"[\x00-\x1f\x7f]", unquote(value)):
        return ""
    parts = urlsplit(value)
    host = parts.hostname or ""
    if parts.scheme != "https" or not host or parts.username or parts.password:
        return ""
    if host == "localhost" or host.endswith((".localhost", ".local", ".internal")) or "." not in host:
        return ""
    try:
        if not ipaddress.ip_address(host).is_global:
            return ""
    except ValueError:
        pass
    return value


def repository_urls(repository, ref, path):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("无效 GitHub 仓库")
    if not ref or not path or path.startswith("/") or ".." in path.split("/") or "\\" in path:
        raise ValueError("README ref/path 无效")
    suffix = f"{repository}/{quote(ref, safe='')}/{quote(path, safe='/')}"
    return "https://raw.githubusercontent.com/" + suffix, f"https://github.com/{repository}/blob/{quote(ref, safe='')}/{quote(path, safe='/')}"


def image_url(value, repository, ref, path):
    value = unescape(value.strip())
    if value.startswith("//"):
        value = "https:" + value
    elif not urlsplit(value).scheme:
        # GitHub treats /assets/foo.png as relative to the repository root.
        local = posixpath.normpath(posixpath.join(posixpath.dirname(path), value)) if not value.startswith("/") else value.lstrip("/")
        if local == ".." or local.startswith("../"):
            return ""
        root = f"https://raw.githubusercontent.com/{repository}/{quote(ref, safe='')}/"
        value = urljoin(root, quote(local, safe="/%?#=&"))
    parts = urlsplit(value)
    if parts.hostname == "github.com" and "/blob/" in parts.path:
        value = urlunsplit(("https", "raw.githubusercontent.com", parts.path.replace("/blob/", "/", 1), parts.query, ""))
    return https_url(value)


def balanced(text, start, opening, closing):
    depth = 1
    index = start + 1
    while index < len(text):
        if text[index] == "\\":
            index += 2
            continue
        if text[index] == opening:
            depth += 1
        elif text[index] == closing:
            depth -= 1
            if depth == 0:
                return index
        index += 1
    return -1


def markdown_images(text, references):
    result = []
    for match in re.finditer(r"(?<!\\)!\[", text):
        start = match.start()
        end_alt = balanced(text, start + 1, "[", "]")
        if end_alt < 0:
            continue
        alt = text[start + 2:end_alt]
        index = end_alt + 1
        target, end = "", end_alt
        if index < len(text) and text[index] == "(":
            end = balanced(text, index, "(", ")")
            if end < 0:
                continue
            target = text[index + 1:end].strip()
            if target.startswith("<"):
                target = target[1:target.find(">")]
            else:
                target = re.split(r'\s+[\"\x27]', target, maxsplit=1)[0]
        elif index < len(text) and text[index] == "[":
            end = text.find("]", index)
            if end < 0:
                continue
            target = references.get((text[index + 1:end] or alt).casefold(), "")
        else:
            target = references.get(alt.casefold(), "")
        if target:
            result.append((start, end + 1, alt, target))
    return result


class ReadmeHTML(HTMLParser):
    def __init__(self, text):
        super().__init__(convert_charrefs=True)
        self.images, self.parts = [], []
        self.hidden = 0
        self.offsets = [0]
        for line in text.splitlines(keepends=True):
            self.offsets.append(self.offsets[-1] + len(line))

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag in ("script", "style", "iframe"):
            self.hidden += 1
        if self.hidden:
            return
        if tag == "img":
            line, col = self.getpos()
            self.images.append((self.offsets[line - 1] + col, attrs.get("alt", ""), attrs.get("src", "")))
        if tag in ("p", "div", "br", "li", "h1", "h2", "h3", "h4"):
            self.parts.append("\n\n")

    def handle_endtag(self, tag):
        if tag in ("script", "style", "iframe"):
            self.hidden = max(0, self.hidden - 1)
        elif not self.hidden and tag in ("p", "div", "li", "h1", "h2", "h3", "h4"):
            self.parts.append("\n\n")

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)


def plain(text):
    parser = ReadmeHTML(text)
    parser.feed(text)
    text = "".join(parser.parts)
    # Keep human-readable link labels, never HTML or Markdown destinations.
    text = re.sub(r"\[([^\[\]]*)\]\([^\n]*?\)|\[([^\[\]]*)\]\[[^\]]*\]", lambda m: m[1] or m[2] or "", text)
    text = re.sub(r"https?://\S+", "", text)
    text = re.sub(r"[`*_~]", "", text)
    return re.sub(r"\s+", " ", unescape(text)).strip(" []•|-\t")


def shortened(text, limit):
    if len(text) <= limit:
        return text
    end = text[:limit - 1]
    # Avoid splitting English words; Chinese text has no spaces to preserve.
    if end[-1].isascii() and " " in end[-25:]:
        end = end.rsplit(" ", 1)[0]
    return end.rstrip(" ,，;；") + "…"


def extract(markdown, repository, ref, path, name):
    _, page_url = repository_urls(repository, ref, path)
    text = re.sub(r"<!--.*?-->|<script\b[^>]*>.*?</script>|<style\b[^>]*>.*?</style>", "", markdown, flags=re.S | re.I)
    # Fenced code must not produce prose or images.
    lines, fence = [], None
    for line in text.splitlines():
        match = re.match(r"^\s{0,3}(`{3,}|~{3,})", line)
        if match and fence is None:
            fence = match[1]
            lines.append("")
        elif fence is not None:
            if re.fullmatch(r"\s*" + re.escape(fence[0]) + "{" + str(len(fence)) + r",}\s*", line):
                fence = None
            lines.append("")
        else:
            lines.append(line)
    text = "\n".join(lines)
    text = re.sub(r"<h([1-6])\b[^>]*>(.*?)</h\1>", lambda m: "\n\n" + ("#" * int(m[1]) + " " if int(m[1]) <= 3 else "") + plain(m[2]) + "\n\n", text, flags=re.I | re.S)
    text = re.sub(r"^(features|特性|功能)[:：]\s*$", r"## \1", text, flags=re.M | re.I)
    references = {m[1].casefold(): m[2].strip("<>") for m in re.finditer(r"^\s{0,3}\[([^\]]+)\]:\s*(<[^>]+>|\S+).*$", text, re.M)}
    text = re.sub(r"^\s{0,3}\[[^\]]+\]:.*$", "", text, flags=re.M)
    headings = [(m.start(), plain(m[1])) for m in re.finditer(r"^\s{0,3}#{1,6}\s+(.+)$", text, re.M)]
    images = markdown_images(text, references)
    parser = ReadmeHTML(text)
    parser.feed(text)
    candidates = [(start, alt, url) for start, _, alt, url in images] + parser.images
    icon, screenshots = "", []
    for start, alt, target in sorted(candidates):
        section = next((title for position, title in reversed(headings) if position < start), "")
        url = image_url(target, repository, ref, path)
        evidence = alt + " " + unquote(target)
        if not url or BAD_IMAGE.search(evidence) or EXCLUDED_SECTION.search(section):
            continue
        if SCREEN.search(evidence) or SCREEN.search(section):
            if url not in screenshots:
                screenshots.append(url)
        elif not icon and ICON.search(evidence):
            icon = url
    # Remove images before selecting text, so linked badges never become descriptions.
    for start, end, _, _ in reversed(images):
        text = text[:start] + "" + text[end:]
    text = re.sub(r"<img\b[^>]*>", "", text, flags=re.I)
    intro, about, features = [], [], []
    mode = "intro"
    for block in re.split(r"\n\s*\n", text):
        for paragraph in re.split(r"(?m)(?=^#{1,6}\s)", block):
            paragraph = paragraph.strip()
            heading = re.match(r"^#{1,6}\s+([^\n]+)(?:\n|$)", paragraph)
            if heading:
                title = plain(heading[1]).strip("#:： ").casefold()
                if title in (name.casefold(), "readme") or paragraph.startswith("# "):
                    pass
                elif title in ("about", "description", "introduction", "overview", "关于", "介绍", "简介", "应用介绍"):
                    mode = "about"
                elif title in ("features", "特性", "功能", "功能特性"):
                    mode = "features"
                elif not intro and paragraph.startswith("### ") and not EXCLUDED_SECTION.search(title):
                    pass
                else:
                    mode = "skip"
                paragraph = paragraph[heading.end():]
            if re.fullmatch(r"(?:features|特性|功能)[:：]?", paragraph, re.I):
                mode = "features"
                continue
            if not paragraph or paragraph.startswith((">", "|", "    ")) or mode == "skip":
                continue
            if mode == "features":
                features.extend(plain(line.lstrip(" -*+")) for line in paragraph.splitlines() if re.match(r"^\s*[-*+]\s+", line))
                continue
            if re.match(r"^\s*(?:[-*+] |\d+\. )", paragraph):
                continue
            # Exclude navigation/language/tool links without losing links within prose.
            if not plain(re.sub(r"\[[^\]]*\](?:\([^)]*\)|\[[^\]]*\])", "", paragraph)):
                continue
            value = plain(paragraph)
            if len(value) >= 24:
                (intro if mode == "intro" else about).append(value)
    paragraphs = list(dict.fromkeys(intro[:1] + about[:2]))
    if features:
        paragraphs.append("\n".join("• " + value for value in features[:6] if value))
    result = {"readmeUrl": page_url}
    if paragraphs:
        result["description"] = shortened("\n\n".join(paragraphs), 1200)
        summary = (intro or about or features)[0]
        result["tagline"] = shortened(re.split(r"(?<=[。！？])|(?<=[.!?])\s+", summary, maxsplit=1)[0], 90)
    if icon:
        result["iconUrl"] = icon
    if screenshots:
        result["screenshots"] = screenshots[:8]
        result["mediaSource"] = page_url
    return result


def merge_metadata(app, record):
    """Content is independent of APK identity, trust decisions and freshness checks."""
    if record and record.get("repository") == app["repository"]:
        app.update({key: value for key, value in record.get("content", {}).items() if key in CONTENT_FIELDS and value})
    overrides = app.pop("metadataOverrides", {})
    if set(overrides) - CONTENT_FIELDS:
        raise ValueError("metadataOverrides 只能修正展示内容")
    app.update(overrides)
    app.pop("readme", None)
    return app
