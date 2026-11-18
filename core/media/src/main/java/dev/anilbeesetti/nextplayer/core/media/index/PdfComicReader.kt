package dev.anilbeesetti.nextplayer.core.media.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PDF 漫画读取器：用系统 [PdfRenderer]（API 21+），无第三方依赖。
 *
 * 与 zip/cbz 的 [ComicArchiveReader] 对齐抽象：
 * - 页数 = 图片条目数；
 * - 渲染第 N 页 = 解第 N 张图；
 * - 渲染第 0 页 = 封面。
 *
 * PdfRenderer 不是线程安全的，且 close 顺序必须是 Page → renderer → fd。
 * 所有方法都是阻塞的，调用方负责切到 IO 线程；多线程访问由本对象级别的锁串行化。
 */
object PdfComicReader {

    /** 数 PDF 页数；打不开/加密/损坏返回 0。 */
    fun pageCount(path: String): Int = synchronized(this) {
        runCatching {
            ParcelFileDescriptor.open(
                File(path),
                ParcelFileDescriptor.MODE_READ_ONLY,
            ).use { fd ->
                PdfRenderer(fd).use { renderer -> renderer.pageCount }
            }
        }.getOrDefault(0)
    }

    /**
     * 渲染指定页为 Bitmap。宽度按 [targetWidthPx] 输出，高度按 PDF 页宽高比等比。
     * 返回 null 表示失败（加密、损坏、页码越界等）。
     */
    fun renderPage(path: String, pageIndex: Int, targetWidthPx: Int): Bitmap? =
        synchronized(this) {
            runCatching {
                ParcelFileDescriptor.open(
                    File(path),
                    ParcelFileDescriptor.MODE_READ_ONLY,
                ).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        if (pageIndex !in 0 until renderer.pageCount) return@use null
                        renderer.openPage(pageIndex).use { page ->
                            val width = targetWidthPx.coerceAtLeast(1)
                            val height = (page.height.toFloat() * width / page.width)
                                .toInt()
                                .coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(
                                width,
                                height,
                                Bitmap.Config.ARGB_8888,
                            )
                            // PDF 页可能带透明通道，先填白底再渲染
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap
                        }
                    }
                }
            }.getOrNull()
        }

    /** 渲染 PDF 第 0 页到缓存文件，返回缓存路径（或 null 失败）。与 zip 封面同一目录。 */
    fun extractCover(context: Context, path: String): String? {
        val cacheDir = File(context.cacheDir, "comic-covers").apply { mkdirs() }
        val coverFile = File(cacheDir, "${md5(path)}.jpg")
        if (coverFile.exists() && coverFile.length() > 0) return coverFile.absolutePath
        val bitmap = renderPage(path, pageIndex = 0, targetWidthPx = COVER_WIDTH_PX)
            ?: return null
        runCatching {
            coverFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
        }.getOrElse { return null }
        return if (coverFile.exists()) coverFile.absolutePath else null
    }

    /** UI 层用：suspend 包装 [pageCount]。 */
    suspend fun pageCountAsync(path: String): Int = withContext(Dispatchers.IO) { pageCount(path) }

    /** UI 层用：suspend 包装 [renderPage]。 */
    suspend fun renderPageAsync(path: String, pageIndex: Int, targetWidthPx: Int): Bitmap? =
        withContext(Dispatchers.IO) { renderPage(path, pageIndex, targetWidthPx) }

    private const val COVER_WIDTH_PX = 480

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
