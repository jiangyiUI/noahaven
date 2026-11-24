package dev.anilbeesetti.nextplayer.feature.books.format

import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 自写的 EPUB 解析，不依赖第三方库。
 *
 * 针对畸形 EPUB 的兜底：
 * - 缺 container.xml：直接扫 .opf；
 * - 缺 NCX/EPUB3 nav：退化为按 spine 顺序 + 正文首个 h1/h3 生成目录；
 * - href 大小写不一致、`../`、URL 编码、反斜杠：统一规范化后查找；
 * - 缺图/坏图：跳过该图，不中断整本导入；
 * - XHTML 非 UTF-8：复用 [TextCodec] 探测。
 */
class EpubParser(private val onLog: (String) -> Unit = {}) {

    data class ParsedChapter(val title: String, val text: String)

    data class ParsedBook(
        val title: String,
        val author: String,
        val chapters: List<ParsedChapter>,
        val coverBytes: ByteArray?,
        val coverFileName: String?,
    )

    class EpubFormatException(message: String) : Exception(message)

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
    )

    fun parse(file: File, imageSink: (name: String, bytes: ByteArray) -> String?): ParsedBook {
        ZipFile(file).use { zip ->
            val index = EntryIndex(zip.entries().asSequence().filterNot { it.isDirectory }.toList())

            val containerEntry = index.find("META-INF/container.xml")
            val opfPath = containerEntry
                ?.let { entry -> readText(zip, entry)?.let(::parseContainerRootFile) }
                ?: index.entries.firstOrNull { it.name.lowercase().endsWith(".opf") }?.name
                ?: throw EpubFormatException("EPUB 缺少 OPF 文件")

            val opfEntry = index.find(opfPath)
                ?: throw EpubFormatException("EPUB 缺少 OPF 文件")
            val opf = readText(zip, opfEntry)
                ?: throw EpubFormatException("无法读取 EPUB 的 OPF 文件")
            val opfDir = opfPath.substringBeforeLast('/', "")

            val manifest = parseManifest(opf)
            val metadata = parseMetadata(opf)
            val spineItems = resolveSpineItems(opf = opf, manifest = manifest)
            if (spineItems.isEmpty()) throw EpubFormatException("EPUB 没有可读章节")

            val toc = LinkedHashMap<String, String>()
            val ncxId = parseSpineTocId(opf)
            val ncxHref = ncxId?.let { manifest[it]?.href }
                ?: manifest.values.firstOrNull {
                    it.mediaType.equals("application/x-dtbncx+xml", ignoreCase = true)
                }?.href
            ncxHref?.let { href ->
                val path = resolvePath(opfDir, href)
                index.find(path)?.let { entry ->
                    readText(zip, entry)?.let { xml ->
                        toc.putAll(parseNcx(xml = xml, baseDir = path.substringBeforeLast('/', "")))
                    }
                }
            }
            val navHref = manifest.values.firstOrNull { it.properties.contains("nav") }?.href
            navHref?.let { href ->
                val path = resolvePath(opfDir, href)
                index.find(path)?.let { entry ->
                    readText(zip, entry)?.let { xml ->
                        toc.putAll(parseNav(xml = xml, baseDir = path.substringBeforeLast('/', "")))
                    }
                }
            }

            val chapters = ArrayList<ParsedChapter>(spineItems.size)
            spineItems.forEachIndexed { position, item ->
                val chapterPath = resolvePath(opfDir, item.href)
                val entry = index.find(chapterPath)
                if (entry == null) {
                    onLog("缺少章节文件：${item.href}")
                    return@forEachIndexed
                }
                val html = readText(zip, entry) ?: return@forEachIndexed
                val chapterDir = chapterPath.substringBeforeLast('/', "")
                val extracted = HtmlText.extract(html) { src ->
                    extractImage(
                        zip = zip,
                        index = index,
                        baseDir = chapterDir,
                        src = src,
                        imageSink = imageSink,
                    )
                }
                if (extracted.text.isBlank()) return@forEachIndexed
                val title = toc[normalizePath(chapterPath)]
                    ?: toc[normalizePath(chapterPath).lowercase()]
                    ?: extracted.heading
                    ?: extracted.title?.takeIf { it.length <= 40 }
                    ?: "第${position + 1}章"
                chapters += ParsedChapter(title = title, text = extracted.text)
            }

            val filtered = chapters.filter {
                it.text.length >= 20 || it.text.contains("[[IMG:")
            }
            val finalChapters = filtered.ifEmpty { chapters }

            val cover = resolveCover(
                zip = zip,
                index = index,
                opf = opf,
                opfDir = opfDir,
                manifest = manifest,
                metadata = metadata,
            )

            return ParsedBook(
                title = metadata.title.ifBlank { file.nameWithoutExtension },
                author = metadata.author,
                chapters = finalChapters.ifEmpty {
                    throw EpubFormatException("EPUB 没有可读正文")
                },
                coverBytes = cover?.first,
                coverFileName = cover?.second,
            )
        }
    }

    private data class Metadata(
        val title: String,
        val author: String,
        val coverId: String?,
    )

    private fun parseContainerRootFile(xml: String): String? =
        Regex("""<rootfile\b[^>]*>""", RegexOption.IGNORE_CASE).find(xml)
            ?.value
            ?.let { attr(tag = it, name = "full-path") }
            ?.takeIf { it.isNotBlank() }

    private fun parseManifest(opf: String): Map<String, ManifestItem> {
        val result = LinkedHashMap<String, ManifestItem>()
        Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf).forEach { match ->
            val tag = match.value
            val id = attr(tag, "id") ?: return@forEach
            val href = attr(tag, "href") ?: return@forEach
            result[id] = ManifestItem(
                id = id,
                href = href,
                mediaType = attr(tag, "media-type").orEmpty(),
                properties = attr(tag, "properties").orEmpty(),
            )
        }
        return result
    }

    private fun parseMetadata(opf: String): Metadata {
        val title = Regex(
            """<dc:title\b[^>]*>(.*?)</dc:title>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(opf)?.groupValues?.get(1)?.let(HtmlText::cleanInline).orEmpty()

        val author = Regex(
            """<dc:creator\b[^>]*>(.*?)</dc:creator>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(opf)?.groupValues?.get(1)?.let(HtmlText::cleanInline).orEmpty()

        val coverId = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(opf)
            .map { it.value }
            .firstOrNull { attr(it, "name").equals("cover", ignoreCase = true) }
            ?.let { attr(it, "content") }

        return Metadata(title = title, author = author, coverId = coverId)
    }

    private fun parseSpineTocId(opf: String): String? =
        Regex("""<spine\b[^>]*>""", RegexOption.IGNORE_CASE).find(opf)
            ?.value
            ?.let { attr(it, "toc") }

    private fun parseSpineRefs(opf: String): List<Pair<String, Boolean>> =
        Regex("""<itemref\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf).mapNotNull { match ->
            val tag = match.value
            val idref = attr(tag, "idref") ?: return@mapNotNull null
            val linear = attr(tag, "linear")?.equals("no", ignoreCase = true) != true
            idref to linear
        }.toList()

    private fun resolveSpineItems(opf: String, manifest: Map<String, ManifestItem>): List<ManifestItem> {
        val htmlItems = manifest.values.filter(::isHtmlItem)
        val fromSpine = parseSpineRefs(opf).mapNotNull { (id, linear) ->
            if (!linear) return@mapNotNull null
            manifest[id]?.takeIf(::isHtmlItem)
        }.distinctBy { it.id }
        if (fromSpine.isNotEmpty()) return fromSpine
        return htmlItems.filterNot { it.properties.contains("nav") }.sortedBy { it.href }
    }

    private fun isHtmlItem(item: ManifestItem): Boolean {
        val type = item.mediaType.lowercase(Locale.ROOT)
        return type.contains("html") || type.contains("xml") && item.href.endsWith(".xhtml") ||
            item.href.endsWith(".xhtml", ignoreCase = true) ||
            item.href.endsWith(".html", ignoreCase = true) ||
            item.href.endsWith(".htm", ignoreCase = true)
    }

    private fun parseNcx(xml: String, baseDir: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val texts = Regex(
            """<text\b[^>]*>(.*?)</text>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(xml).toList()
        val contents = Regex("""<content\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(xml).toList()
        var textIndex = 0
        contents.forEach { content ->
            val src = attr(content.value, "src") ?: return@forEach
            while (textIndex + 1 < texts.size &&
                texts[textIndex + 1].range.first < content.range.first
            ) {
                textIndex++
            }
            val label = texts.getOrNull(textIndex)
                ?.takeIf { it.range.first < content.range.first }
                ?.groupValues?.get(1)
                ?.let(HtmlText::cleanInline)
                .orEmpty()
            if (label.isNotBlank()) {
                result.putIfAbsent(normalizePath(resolvePath(baseDir, src)), label)
            }
        }
        return result
    }

    private fun parseNav(xml: String, baseDir: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val navSection = Regex(
            """<nav\b[^>]*epub:type\s*=\s*(?:"[^"]*toc[^"]*"|'[^']*toc[^']*')[^>]*>(.*?)</nav>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(xml)?.groupValues?.get(1) ?: return result

        Regex(
            """<a\b[^>]*>.*?</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(navSection).forEach { match ->
            val tag = match.value
            val href = attr(tag, "href") ?: return@forEach
            val label = HtmlText.cleanInline(tag.replace(Regex("""<[^>]+>"""), " "))
            if (label.isNotBlank()) {
                result.putIfAbsent(normalizePath(resolvePath(baseDir, href)), label)
            }
        }
        return result
    }

    private fun extractImage(
        zip: ZipFile,
        index: EntryIndex,
        baseDir: String,
        src: String,
        imageSink: (name: String, bytes: ByteArray) -> String?,
    ): String? {
        if (src.startsWith("data:", ignoreCase = true)) return null
        val path = resolvePath(baseDir, src)
        val entry = index.find(path) ?: run {
            onLog("缺少图片：$src")
            return null
        }
        val bytes = runCatching { zip.getInputStream(entry).use { it.readBytes() } }
            .getOrElse {
                onLog("图片读取失败：$src")
                return null
            }
        if (bytes.size > 8 * 1024 * 1024) return null
        val extension = imageExtension(path = path, bytes = bytes)
        val name = "img_" + sha1(entry.name).take(16) + extension
        return runCatching { imageSink(name, bytes) }
            .getOrElse {
                onLog("图片写入失败：$src")
                null
            }
    }

    private fun resolveCover(
        zip: ZipFile,
        index: EntryIndex,
        opf: String,
        opfDir: String,
        manifest: Map<String, ManifestItem>,
        metadata: Metadata,
    ): Pair<ByteArray, String>? {
        val coverItem = metadata.coverId?.let(manifest::get)
            ?: manifest.values.firstOrNull {
                it.properties.split(' ', ',').any { key -> key.equals("cover-image", ignoreCase = true) }
            }
            ?: return null
        val path = resolvePath(opfDir, coverItem.href)
        val entry = index.find(path) ?: return null
        val bytes = runCatching { zip.getInputStream(entry).use { it.readBytes() } }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return bytes to ("cover" + imageExtension(path = path, bytes = bytes))
    }

    private fun readText(zip: ZipFile, entry: ZipEntry): String? =
        runCatching {
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            TextCodec.decode(bytes).text
        }.getOrElse {
            onLog("读取失败：${entry.name}")
            null
        }

    private fun normalizePath(path: String): String {
        val decoded = decodeUrl(path.replace('\\', '/'))
        val withoutSuffix = decoded.substringBefore('#').substringBefore('?')
        val stack = ArrayList<String>()
        withoutSuffix.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack += segment
            }
        }
        return stack.joinToString("/")
    }

    private fun resolvePath(baseDir: String, href: String): String {
        val cleaned = href.trim()
        val combined = if (cleaned.startsWith("/")) {
            cleaned.removePrefix("/")
        } else if (baseDir.isBlank()) {
            cleaned
        } else {
            "$baseDir/$cleaned"
        }
        return normalizePath(combined)
    }

    private fun decodeUrl(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value)

    private fun attr(tag: String, name: String): String? {
        val regex = Regex(
            """\b${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",
            RegexOption.IGNORE_CASE,
        )
        val match = regex.find(tag) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
    }

    private fun imageExtension(path: String, bytes: ByteArray): String {
        val fromName = path.substringAfterLast('/', "").substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        if (fromName in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) {
            return ".$fromName"
        }
        return when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> ".jpg"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> ".png"
            bytes.size >= 6 && bytes[0] == 'G'.code.toByte() -> ".gif"
            bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() -> ".webp"
            else -> ".img"
        }
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private class EntryIndex(val entries: List<ZipEntry>) {
        private val exact = entries.associateBy { it.name }
        private val lower = entries.associateBy { it.name.lowercase(Locale.ROOT) }
        private val byFileName = entries.groupBy { it.name.substringAfterLast('/').lowercase(Locale.ROOT) }

        fun find(path: String): ZipEntry? {
            if (path.isBlank()) return null
            return exact[path]
                ?: lower[path.lowercase(Locale.ROOT)]
                ?: byFileName[path.substringAfterLast('/').lowercase(Locale.ROOT)]?.firstOrNull()
        }
    }
}
