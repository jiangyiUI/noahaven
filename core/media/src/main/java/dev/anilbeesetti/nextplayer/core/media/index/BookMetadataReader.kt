package dev.anilbeesetti.nextplayer.core.media.index

import java.io.File
import java.net.URLDecoder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 扫描阶段只读 EPUB 的 OPF 元数据（标题 / 作者 / 封面），不解正文。
 * 正文解析在 feature:books 的 [EpubParser] 里完成；这里保持轻量，避免书架扫描卡顿。
 */
internal object BookMetadataReader {

    data class Metadata(
        val title: String,
        val author: String,
        val coverBytes: ByteArray? = null,
        val coverExtension: String? = null,
    )

    fun readEpub(file: File): Metadata? = runCatching {
        ZipFile(file).use { zip ->
            val index = EntryIndex(zip.entries().asSequence().filterNot { it.isDirectory }.toList())
            val container = index.find("META-INF/container.xml")?.let { entry ->
                readText(zip, entry)
            }
            val opfPath = container?.let(::parseContainerRootFile)
                ?: index.entries.firstOrNull { it.name.lowercase().endsWith(".opf") }?.name
                ?: return@runCatching null
            val opfEntry = index.find(opfPath) ?: return@runCatching null
            val opf = readText(zip, opfEntry) ?: return@runCatching null

            val title = Regex(
                """<dc:title\b[^>]*>(.*?)</dc:title>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).find(opf)?.groupValues?.get(1)?.let(::cleanInline).orEmpty()
            val author = Regex(
                """<dc:creator\b[^>]*>(.*?)</dc:creator>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).find(opf)?.groupValues?.get(1)?.let(::cleanInline).orEmpty()

            val opfDir = opfPath.substringBeforeLast('/', "")
            val cover = resolveCover(zip, index, opf, opfDir)
            Metadata(
                title = title.ifBlank { file.nameWithoutExtension },
                author = author,
                coverBytes = cover?.first,
                coverExtension = cover?.second,
            )
        }
    }.getOrNull()

    private fun resolveCover(
        zip: ZipFile,
        index: EntryIndex,
        opf: String,
        opfDir: String,
    ): Pair<ByteArray, String>? {
        val manifest = LinkedHashMap<String, String>()
        val properties = HashMap<String, String>()
        Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf).forEach { match ->
            val tag = match.value
            val id = attr(tag, "id") ?: return@forEach
            val href = attr(tag, "href") ?: return@forEach
            manifest[id] = href
            properties[id] = attr(tag, "properties").orEmpty()
        }
        val coverId = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(opf)
            .map { it.value }
            .firstOrNull { attr(it, "name").equals("cover", ignoreCase = true) }
            ?.let { attr(it, "content") }
        val href = coverId?.let(manifest::get)
            ?: manifest.entries.firstOrNull { (id, _) ->
                properties[id].orEmpty().split(' ', ',').any {
                    it.equals("cover-image", ignoreCase = true)
                }
            }?.value
            ?: return null
        val path = resolvePath(opfDir, href)
        val entry = index.find(path) ?: return null
        val bytes = runCatching { zip.getInputStream(entry).use { it.readBytes() } }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() && it.size <= 8 * 1024 * 1024 }
            ?: return null
        return bytes to imageExtension(path)
    }

    private fun readText(zip: ZipFile, entry: ZipEntry): String? = runCatching {
        zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
    }.getOrNull()

    private fun parseContainerRootFile(xml: String): String? =
        Regex("""<rootfile\b[^>]*>""", RegexOption.IGNORE_CASE).find(xml)
            ?.value
            ?.let { attr(it, "full-path") }
            ?.takeIf { it.isNotBlank() }

    private fun cleanInline(value: String): String = value
        .replace(Regex("""<[^>]+>"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun attr(tag: String, name: String): String? {
        val match = Regex(
            """\b${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",
            RegexOption.IGNORE_CASE,
        ).find(tag) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
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

    private fun normalizePath(path: String): String {
        val decoded = runCatching {
            URLDecoder.decode(path.replace('\\', '/').replace("+", "%2B"), "UTF-8")
        }.getOrDefault(path.replace('\\', '/'))
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

    private fun imageExtension(path: String): String {
        val ext = path.substringAfterLast('/', "").substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        return if (ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) ".$ext" else ".jpg"
    }

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
