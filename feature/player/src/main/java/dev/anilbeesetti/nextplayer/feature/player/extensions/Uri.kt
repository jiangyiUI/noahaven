package dev.anilbeesetti.nextplayer.feature.player.extensions

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import dev.anilbeesetti.nextplayer.core.common.extensions.convertToUTF8
import dev.anilbeesetti.nextplayer.core.common.extensions.getFilenameFromUri
import dev.anilbeesetti.nextplayer.core.common.extensions.subtitleCacheDir
import java.io.File
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Uri.getSubtitleMime(): String = when {
    path?.endsWith(suffix = ".ssa") == true || path?.endsWith(suffix = ".ass") == true -> {
        MimeTypes.TEXT_SSA
    }

    path?.endsWith(suffix = ".vtt") == true -> {
        MimeTypes.TEXT_VTT
    }

    path?.endsWith(suffix = ".ttml") == true || path?.endsWith(suffix = ".xml") == true ||
        path?.endsWith(suffix = ".dfxp") == true -> {
        MimeTypes.APPLICATION_TTML
    }

    else -> {
        MimeTypes.APPLICATION_SUBRIP
    }
}

val Uri.isSchemaContent: Boolean
    get() = ContentResolver.SCHEME_CONTENT.equals(
        other = scheme,
        ignoreCase = true,
    )

suspend fun Context.uriToSubtitleConfiguration(
    uri: Uri,
    subtitleEncoding: String = "",
    isSelected: Boolean = false,
    label: String? = null,
    filename: String? = null,
    useMediaSourceForNetwork: Boolean = false,
): MediaItem.SubtitleConfiguration {
    val charset = if (subtitleEncoding.isNotEmpty() && Charset.isSupported(subtitleEncoding)) {
        Charset.forName(subtitleEncoding)
    } else {
        null
    }
    val subtitleLabel = label ?: filename ?: getFilenameFromUri(uri)
    val isLrc = isLrcSubtitle(filename = filename, uri = uri)
    // LRC 不是 Media3 原生支持的字幕格式：先在应用层转成 SRT 缓存，再复用现有字幕解码/渲染链路。
    val resolvedUri = when {
        isLrc -> convertLrcToSrt(uri = uri, charset = charset)
        useMediaSourceForNetwork && uri.scheme in listOf("http", "https") -> uri
        else -> convertToUTF8(
            uri = uri,
            charset = charset,
        )
    }
    val mimeType = if (isLrc) {
        MimeTypes.APPLICATION_SUBRIP
    } else {
        (filename?.let(Uri::parse) ?: uri).getSubtitleMime()
    }
    return MediaItem.SubtitleConfiguration.Builder(resolvedUri).apply {
        setId(uri.toString())
        setMimeType(mimeType)
        setLabel(subtitleLabel)
        if (isSelected) setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
    }.build()
}

/** Accept the documented Uri[] and legacy clients sending an ArrayList<Uri>. */
@Suppress("DEPRECATION")
fun Bundle.getParcelableUriArray(key: String): List<Uri?>? = when (val value = get(key)) {
    is Array<*> -> value.map { it as? Uri }
    is ArrayList<*> -> value.map { it as? Uri }
    else -> null
}

/** 按文件名/路径扩展名判断是否为 LRC 歌词字幕。 */
private fun isLrcSubtitle(filename: String?, uri: Uri): Boolean {
    val name = filename ?: uri.path ?: return false
    return name.endsWith(suffix = ".lrc", ignoreCase = true)
}

private val LRC_TIMESTAMP_REGEX = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
private val LRC_WORD_TIMESTAMP_REGEX = Regex("""<\d{1,2}:\d{1,2}(?:[.:]\d{1,3})?>""")

/**
 * 将 LRC 歌词文件转换为 SRT 字幕缓存，复用现有字幕解码/渲染链路。
 * 转换失败时回退原 uri，避免阻断播放。
 */
private suspend fun Context.convertLrcToSrt(uri: Uri, charset: Charset?): Uri =
    withContext(Dispatchers.IO) {
        try {
            // 先复用现有编码处理，统一得到 UTF-8 文本来源
            val utf8Uri = convertToUTF8(uri = uri, charset = charset)
            val rawText = when (utf8Uri.scheme) {
                "http", "https", "ftp" -> URL(utf8Uri.toString()).openStream().use { stream ->
                    stream.reader(StandardCharsets.UTF_8).buffered().use { it.readText() }
                }
                else -> contentResolver.openInputStream(utf8Uri)?.use { stream ->
                    stream.reader(StandardCharsets.UTF_8).buffered().use { it.readText() }
                }
            } ?: return@withContext uri
            // 过滤 NUL 等控制字符：部分 lrc 文件末尾会带 0x00，Media3 的 SRT 解码器读到后
            // 会整条轨道解析失败、一条 cue 都不输出，导致歌词完全不显示。
            val lrcText = rawText.filter { it >= ' ' || it == '\n' || it == '\r' || it == '\t' }

            val srtText = parseLrcToSrt(lrcText = lrcText)
            // 缓存文件名只用 uri 哈希，不拼原始 baseName：含多字节字符的长文件名经
            // percent-encode 后会膨胀（一个日文/中文变 9 字符），拼接后可能超过
            // Android 文件名 255 字符上限，导致写入失败、回退原 uri 后解码失败不显示。
            val suffix = uri.toString().hashCode().toUInt().toString(radix = 16)
            val cacheFile = File(subtitleCacheDir, "$suffix.srt")
            cacheFile.outputStream().writer(StandardCharsets.UTF_8).buffered().use { writer ->
                writer.write(srtText)
            }
            Uri.fromFile(cacheFile)
        } catch (exception: Exception) {
            exception.printStackTrace()
            uri
        }
    }

/**
 * 解析 LRC 文本为 SRT 文本。
 * - 过滤 `[ti:]`/`[ar:]`/`[al:]` 等元数据行（不含合法时间戳的行会被自然跳过）
 * - 支持一行多时间戳 `[t1][t2]同句歌词`
 * - 支持全局偏移 `[offset:±ms]`
 * - 逐字标签 `<mm:ss.xx>` 只去掉标签、保留文本（整行展示，不做逐字高亮）
 * - 相同时间戳的多行（双语/翻译歌词）合并为一条提示、按文件顺序换行堆叠，
 *   避免首行被赋 0 时长而不显示；每组结束时间取下一组开始时间，末行给 10 秒窗口
 */
private fun parseLrcToSrt(lrcText: String): String {
    val entries = mutableListOf<Pair<Long, String>>() // startUs -> text
    var offsetMs = 0L
    for (rawLine in lrcText.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        if (line.startsWith(prefix = "[offset:", ignoreCase = true)) {
            runCatching {
                offsetMs = line.removePrefix(prefix = "[offset:")
                    .substringBefore(delimiter = ']')
                    .trim()
                    .toLong()
            }
            continue
        }
        val timestamps = LRC_TIMESTAMP_REGEX.findAll(line).map { match ->
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            val fraction = match.groupValues[3].ifEmpty { "0" }
            val millis = when (fraction.length) {
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                else -> fraction.take(n = 3).toLong()
            }
            (minutes * 60L + seconds) * 1000L + millis
        }.toList()
        if (timestamps.isEmpty()) continue
        val text = LRC_WORD_TIMESTAMP_REGEX.replace(
            input = LRC_TIMESTAMP_REGEX.replace(input = line, replacement = ""),
            replacement = "",
        ).trim()
        if (text.isEmpty()) continue
        for (startMs in timestamps) {
            entries.add(element = (startMs - offsetMs) * 1000L to text)
        }
    }
    entries.sortBy { it.first }
    // 相同时间戳的多行合并为一条提示（双语/翻译歌词按文件顺序换行堆叠），
    // 否则首行结束时间会等于自身开始时间、时长为 0 而不显示。
    val groups = mutableListOf<Pair<Long, String>>()
    for ((startUs, text) in entries) {
        val last = groups.lastOrNull()
        if (last != null && last.first == startUs) {
            groups[groups.lastIndex] = startUs to last.second + "\n" + text
        } else {
            groups.add(startUs to text)
        }
    }
    val builder = StringBuilder()
    for (index in groups.indices) {
        val (startUs, text) = groups[index]
        val endUs = if (index + 1 < groups.size) {
            groups[index + 1].first
        } else {
            startUs + 10_000_000L
        }
        builder.append(index + 1).append('\n')
        builder.append(formatSrtTime(startUs))
            .append(" --> ")
            .append(formatSrtTime(endUs))
            .append('\n')
        builder.append(text).append("\n\n")
    }
    return builder.toString()
}

private fun formatSrtTime(timeUs: Long): String {
    val totalMs = (timeUs / 1000L).coerceAtLeast(minimumValue = 0L)
    val hours = totalMs / 3_600_000L
    val minutes = (totalMs % 3_600_000L) / 60_000L
    val seconds = (totalMs % 60_000L) / 1_000L
    val millis = totalMs % 1_000L
    return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
}
