package dev.anilbeesetti.nextplayer.core.media.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import java.io.File
import java.io.RandomAccessFile
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
 * 加密 PDF：Android 15（API 35）起 [PdfRenderer] 支持 LoadParams 传密码，
 * 更老的系统没有该 API，只能提示不支持（[supportsPassword]）。
 *
 * PdfRenderer 不是线程安全的，且 close 顺序必须是 Page → renderer → fd。
 * 所有方法都是阻塞的，调用方负责切到 IO 线程；多线程访问由本对象级别的锁串行化。
 */
object PdfComicReader {

    /** 当前系统能否打开加密 PDF（Android 15 起）。 */
    val supportsPassword: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    /** 数 PDF 页数；打不开/加密未提供密码/损坏返回 0。 */
    fun pageCount(path: String, password: String? = null): Int = synchronized(this) {
        runCatching {
            ParcelFileDescriptor.open(
                File(path),
                ParcelFileDescriptor.MODE_READ_ONLY,
            ).use { fd ->
                openRenderer(fd, password).use { renderer -> renderer.pageCount }
            }
        }.getOrDefault(0)
    }

    /**
     * 渲染指定页为 Bitmap。宽度按 [targetWidthPx] 输出，高度按 PDF 页宽高比等比。
     * 返回 null 表示失败（加密、损坏、页码越界等）。
     */
    fun renderPage(
        path: String,
        pageIndex: Int,
        targetWidthPx: Int,
        password: String? = null,
    ): Bitmap? = synchronized(this) {
        runCatching {
            ParcelFileDescriptor.open(
                File(path),
                ParcelFileDescriptor.MODE_READ_ONLY,
            ).use { fd ->
                openRenderer(fd, password).use { renderer ->
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
    fun extractCover(context: Context, path: String, password: String? = null): String? {
        val cacheDir = File(context.cacheDir, "comic-covers").apply { mkdirs() }
        val coverFile = File(cacheDir, "${md5(path)}.jpg")
        if (coverFile.exists() && coverFile.length() > 0) return coverFile.absolutePath
        val bitmap = renderPage(
            path = path,
            pageIndex = 0,
            targetWidthPx = COVER_WIDTH_PX,
            password = password,
        ) ?: return null
        runCatching {
            coverFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
        }.getOrElse { return null }
        return if (coverFile.exists()) coverFile.absolutePath else null
    }

    /**
     * 粗略判断 PDF 是否加密：在文件里找 `/Encrypt` 标记。
     * 打开失败且这里命中，就认为「需要密码」而不是「文件损坏」。
     */
    fun isEncrypted(path: String): Boolean = runCatching {
        val file = File(path)
        if (!file.exists() || file.length() <= 0) return false
        val needle = "/Encrypt".toByteArray(Charsets.US_ASCII)
        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(SCAN_CHUNK_SIZE + needle.size)
            var carry = 0
            while (true) {
                val read = raf.read(buffer, carry, SCAN_CHUNK_SIZE)
                if (read <= 0) break
                val length = carry + read
                if (indexOf(buffer, length, needle)) return true
                carry = needle.size - 1
                System.arraycopy(buffer, length - carry, buffer, 0, carry)
            }
        }
        false
    }.getOrDefault(false)

    private fun indexOf(buffer: ByteArray, length: Int, needle: ByteArray): Boolean {
        val limit = length - needle.size
        if (limit < 0) return false
        outer@ for (i in 0..limit) {
            for (j in needle.indices) {
                if (buffer[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun openRenderer(fd: ParcelFileDescriptor, password: String?): PdfRenderer =
        if (supportsPassword && !password.isNullOrEmpty()) {
            openWithPassword(fd, password)
        } else {
            PdfRenderer(fd)
        }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun openWithPassword(fd: ParcelFileDescriptor, password: String): PdfRenderer =
        PdfRenderer(
            fd,
            LoadParams.Builder().setPassword(password).build(),
        )

    /** UI 层用：suspend 包装 [pageCount]。 */
    suspend fun pageCountAsync(path: String, password: String? = null): Int =
        withContext(Dispatchers.IO) { pageCount(path, password) }

    /** UI 层用：suspend 包装 [renderPage]。 */
    suspend fun renderPageAsync(
        path: String,
        pageIndex: Int,
        targetWidthPx: Int,
        password: String? = null,
    ): Bitmap? = withContext(Dispatchers.IO) { renderPage(path, pageIndex, targetWidthPx, password) }

    private const val COVER_WIDTH_PX = 480
    private const val SCAN_CHUNK_SIZE = 64 * 1024

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
