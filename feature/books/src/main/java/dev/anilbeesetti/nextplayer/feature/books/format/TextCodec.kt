package dev.anilbeesetti.nextplayer.feature.books.format

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * TXT 编码探测：
 * 1. BOM 优先；
 * 2. 无 BOM 时用零字节分布判断 UTF-16；
 * 3. UTF-8 严格抽样（截断到字符边界，避免把多字节字符切一半误判）；
 * 4. 整体严格复验 UTF-8，失败再退 GB18030 / GBK。
 *
 * 很多中文 TXT 是 GBK，且末尾可能被截断半个汉字，因此 GBK 回退使用宽松解码，
 * 只丢弃末尾的替换符，保证不会因为最后一两个字节整本打不开。
 */
object TextCodec {

    data class Decoded(val text: String, val charsetName: String)

    private val UTF_8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF_16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF_16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    fun decode(bytes: ByteArray): Decoded {
        if (bytes.isEmpty()) return Decoded(text = "", charsetName = "UTF-8")

        if (bytes.startsWith(UTF_8_BOM)) {
            return decodeStrict(bytes, size = bytes.size, offset = 3, Charset.forName("UTF-8"))
                ?.let { Decoded(it, "UTF-8") }
                ?: Decoded(String(bytes, 3, bytes.size - 3, Charset.forName("UTF-8")), "UTF-8")
        }
        if (bytes.startsWith(UTF_16_LE_BOM)) {
            return Decoded(
                String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE")),
                "UTF-16LE",
            )
        }
        if (bytes.startsWith(UTF_16_BE_BOM)) {
            return Decoded(
                String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE")),
                "UTF-16BE",
            )
        }

        detectUtf16WithoutBom(bytes)?.let { charset ->
            return Decoded(String(bytes, charset), charset.name())
        }

        val sampleLength = minOf(bytes.size, 256 * 1024)
        val sample = bytes.copyOf(sampleLength)
        if (isUtf8Sample(sample = sample, truncated = sampleLength < bytes.size)) {
            val strict = decodeStrict(bytes, size = bytes.size, offset = 0, Charset.forName("UTF-8"))
            if (strict != null) return Decoded(strict, "UTF-8")
        }

        val gb18030 = Charset.forName("GB18030")
        decodeStrict(bytes, size = bytes.size, offset = 0, gb18030)?.let {
            return Decoded(it, "GB18030")
        }
        val lenient = String(bytes, gb18030).trimEnd('\uFFFD')
        return Decoded(lenient, "GB18030")
    }

    private fun isUtf8Sample(sample: ByteArray, truncated: Boolean): Boolean {
        if (decodeStrict(sample, size = sample.size, offset = 0, Charset.forName("UTF-8")) != null) {
            return true
        }
        if (!truncated) return false
        // 抽样尾部可能刚好切在一个多字节字符中间，最多回退 3 个字节再验一次。
        for (trim in 1..3) {
            if (sample.size - trim <= 0) break
            val trimmed = sample.copyOf(sample.size - trim)
            if (decodeStrict(trimmed, size = trimmed.size, offset = 0, Charset.forName("UTF-8")) != null) {
                return true
            }
        }
        return false
    }

    private fun detectUtf16WithoutBom(bytes: ByteArray): Charset? {
        if (bytes.size < 16) return null
        val size = minOf(bytes.size, 4096) and 0x7FFFFFFE
        if (size < 16) return null
        var evenZero = 0
        var oddZero = 0
        var index = 0
        while (index + 1 < size) {
            if (bytes[index] == 0.toByte()) evenZero++
            if (bytes[index + 1] == 0.toByte()) oddZero++
            index += 2
        }
        val pairs = size / 2
        val evenRatio = evenZero.toFloat() / pairs
        val oddRatio = oddZero.toFloat() / pairs
        return when {
            oddRatio > 0.3f && evenRatio < 0.05f -> Charset.forName("UTF-16LE")
            evenRatio > 0.3f && oddRatio < 0.05f -> Charset.forName("UTF-16BE")
            else -> null
        }
    }

    private fun decodeStrict(
        bytes: ByteArray,
        size: Int,
        offset: Int,
        charset: Charset,
    ): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, size - offset))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }
}
