package dev.anilbeesetti.nextplayer.core.media.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 读取音频文件的内嵌封面。只读本地文件，不联网获取；没有封面时返回 null，由界面显示缺省图。
 *
 * 列表滚动会频繁请求封面，这里用 [LruCache] 做进程内缓存，并按展示尺寸降采样解码。
 */
object AudioArtwork {

    private const val CACHE_SIZE_BYTES = 32 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(
        context: Context,
        pathOrUri: String,
        maxSizePx: Int = 512,
    ): Bitmap? = withContext(context = Dispatchers.IO) {
        cache.get(pathOrUri) ?: runCatching {
            val bytes = readEmbeddedPicture(context, pathOrUri) ?: return@runCatching null
            decodeSampledBitmap(bytes = bytes, maxSizePx = maxSizePx)
        }.getOrNull()?.also { bitmap -> cache.put(pathOrUri, bitmap) }
    }

    private fun readEmbeddedPicture(context: Context, pathOrUri: String): ByteArray? {
        val uri = pathOrUri.toUri()
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "content") {
                retriever.setDataSource(context, uri)
            } else {
                // 兼容 file:// 与纯文件路径（.nomedia 目录里的音频走 file:// uri）
                val file = File(uri.path ?: pathOrUri)
                if (!file.isFile) return null
                retriever.setDataSource(file.absolutePath)
            }
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeSampledBitmap(bytes: ByteArray, maxSizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        var sampleSize = 1
        while (width / (sampleSize * 2) >= maxSizePx && height / (sampleSize * 2) >= maxSizePx) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}

private fun String.toUri() = android.net.Uri.parse(this)
