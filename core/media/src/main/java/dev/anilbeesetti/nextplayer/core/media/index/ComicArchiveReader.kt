package dev.anilbeesetti.nextplayer.core.media.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.anilbeesetti.nextplayer.core.common.Utils
import dev.anilbeesetti.nextplayer.core.database.entities.ComicFileIndexEntity
import dev.anilbeesetti.nextplayer.core.model.ComicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader

/**
 * 打开一个 zip/cbz，按文件名返回内部的图片条目列表（天然排序后的 zip entry）
 * 以及按需解指定 entry 的 InputStream 给查看器用。
 *
 * zip4j 不支持真正的 random access（它是流式 + central directory 随机定位），
 * 所以每次读图都重新 open 一次 zip（开销很小，central directory seek 很快）。
 */
class ComicArchiveReader(private val zipPath: String) : AutoCloseable {

    private val zip: ZipFile by lazy { ZipFile(zipPath) }

    /** 按文件名自然排序后的图片条目（路径 + entry）。扫描时一次性读好缓存。 */
    fun listImageEntries(): List<ImageEntry> {
        val all = zip.fileHeaders
        val imageHeaders = all.filter { header ->
            !header.isDirectory && header.fileName.substringAfterLast(
                '.',
                "",
            )
                .lowercase() in IMAGE_EXTS
        }
        // 按文件名自然排序（子目录名参与排序，保留原始层级）
        return imageHeaders
            .sortedWith(compareBy<FileHeader> { it.fileName.lowercase() })
            .map {
                ImageEntry(
                    it.fileName,
                    it.uncompressedSize,
                )
            }
    }

    /** 解指定 entry 为 InputStream。调用方负责 close（读一次就够）。 */
    fun openEntryStream(entryName: String): java.io.InputStream? = runCatching {
        zip.getInputStream(zip.getFileHeader(entryName))
    }.getOrNull()

    /** 压缩包本身是否加密（加密时 central directory 仍可读，能列出条目名）。 */
    val isEncrypted: Boolean get() = runCatching { zip.isEncrypted }.getOrDefault(false)

    /** 设置解压密码；传 null 表示清空。 */
    fun setPassword(password: String?) {
        runCatching { zip.setPassword(password?.toCharArray()) }
    }

    /** 完整读出一个 entry 的字节。加密包用「读完」触发 CRC / AES 校验，从而验证密码是否正确。 */
    fun readEntryBytes(entryName: String): ByteArray? = runCatching {
        val header = zip.getFileHeader(entryName) ?: return@runCatching null
        zip.getInputStream(header).use { it.readBytes() }
    }.getOrNull()

    override fun close() {
        runCatching { zip.close() }
    }

    data class ImageEntry(val entryName: String, val size: Long)

    companion object {
        private val IMAGE_EXTS = setOf(
            "jpg",
            "jpeg",
            "png",
            "gif",
            "webp",
            "bmp",
            "avif",
            "heic",
            "tiff",
        )

        /**
         * UI 层用：拿漫画的「页标识」列表。
         * zip/cbz → 内部图片 entry 名列表；pdf → 页码字符串列表（"0","1",...）。
         * 这样 ComicViewer 可以用同一套 entries 驱动，不用区分格式。
         */
        suspend fun listImageEntryNames(zipPath: String, password: String? = null): List<String> =
            withContext(Dispatchers.IO) {
                when (val result = openComic(zipPath, password)) {
                    is ComicOpenResult.Success -> result.entries
                    else -> emptyList()
                }
            }

        /**
         * UI 层用：打开漫画并区分「需要密码 / 密码错误 / 不支持 / 失败」。
         * 加密 zip 会完整读第一张图来验证密码；加密 pdf 依赖 API 35+ 的 LoadParams。
         */
        suspend fun openComic(zipPath: String, password: String?): ComicOpenResult =
            withContext(Dispatchers.IO) {
                if (isPdf(zipPath)) openPdf(zipPath, password) else openZip(zipPath, password)
            }

        private fun openZip(zipPath: String, password: String?): ComicOpenResult {
            val reader = ComicArchiveReader(zipPath)
            return reader.use {
                val entries = runCatching {
                    it.listImageEntries().map { entry -> entry.entryName }
                }.getOrDefault(emptyList())
                if (entries.isEmpty()) return@use ComicOpenResult.Failed
                if (!it.isEncrypted) return@use ComicOpenResult.Success(entries)
                if (password.isNullOrEmpty()) return@use ComicOpenResult.PasswordRequired
                it.setPassword(password)
                if (it.readEntryBytes(entries.first()) != null) {
                    ComicOpenResult.Success(entries)
                } else {
                    ComicOpenResult.WrongPassword
                }
            }
        }

        private fun openPdf(pdfPath: String, password: String?): ComicOpenResult {
            val count = PdfComicReader.pageCount(pdfPath, password)
            if (count > 0) return ComicOpenResult.Success(List(count) { it.toString() })
            // 打不开：区分「加密」和「损坏」，加密再按系统能力决定能否输密码
            if (!PdfComicReader.isEncrypted(pdfPath)) return ComicOpenResult.Failed
            if (!PdfComicReader.supportsPassword) return ComicOpenResult.UnsupportedEncrypted
            return if (password.isNullOrEmpty()) {
                ComicOpenResult.PasswordRequired
            } else {
                ComicOpenResult.WrongPassword
            }
        }

        /**
         * UI 层用：按「页标识」解一页为 Bitmap。
         * zip/cbz → entryName 是 zip 内路径；pdf → entryName 是页码字符串。
         */
        suspend fun decodeEntryBitmap(
            zipPath: String,
            entryName: String,
            password: String? = null,
        ): Bitmap? = withContext(Dispatchers.IO) {
            if (isPdf(zipPath)) {
                val pageIndex = entryName.toIntOrNull() ?: return@withContext null
                // PDF 渲染宽度按 1080 给，够绝大多数屏幕用；宽图在 UI 层再放大
                PdfComicReader.renderPage(
                    path = zipPath,
                    pageIndex = pageIndex,
                    targetWidthPx = 1080,
                    password = password,
                )
            } else {
                runCatching {
                    ZipFile(zipPath).use { zip ->
                        if (!password.isNullOrEmpty()) {
                            zip.setPassword(password.toCharArray())
                        }
                        zip.getInputStream(zip.getFileHeader(entryName)).use {
                            BitmapFactory.decodeStream(it)
                        }
                    }
                }.getOrNull()
            }
        }

        /** UI 层用：解 zip 的第一张图片到 Bitmap（封面兜底）。 */
        suspend fun decodeFirstEntryBitmap(zipPath: String): Bitmap? = withContext(Dispatchers.IO) {
            runCatching {
                ZipFile(zipPath).use { zip ->
                    val first = zip.fileHeaders
                        .filter {
                            !it.isDirectory &&
                                IMAGE_EXTS.contains(
                                    it.fileName.substringAfterLast(
                                        '.',
                                        "",
                                    ).lowercase(),
                                )
                        }
                        .minByOrNull { it.fileName.lowercase() }
                        ?: return@use null
                    zip.getInputStream(first).use { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull()
        }

        /** 扫描时用：解 zip 的第一张图 / pdf 的第 0 页到缓存文件，返回缓存路径（或 null 失败）。 */
        fun extractCover(
            context: Context,
            zipPath: String,
            imageEntries: List<ImageEntry>,
            password: String? = null,
        ): String? {
            if (isPdf(zipPath)) return PdfComicReader.extractCover(context, zipPath, password)
            if (imageEntries.isEmpty()) return null
            val first = imageEntries.first().entryName
            val cacheDir = File(
                context.cacheDir,
                "comic-covers",
            ).apply { mkdirs() }
            val md5 = md5(zipPath)
            val coverFile = File(
                cacheDir,
                "$md5.jpg",
            )
            if (coverFile.exists() && coverFile.length() > 0) return coverFile.absolutePath
            runCatching {
                ComicArchiveReader(zipPath).use { reader ->
                    reader.setPassword(password)
                    reader.readEntryBytes(first)?.let { bytes ->
                        coverFile.writeBytes(bytes)
                    }
                }
            }.getOrElse { return null }
            return if (coverFile.exists()) coverFile.absolutePath else null
        }

        private fun md5(input: String): String {
            val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** 打开漫画的结果：成功拿到页列表，或需要用户处理密码 / 不支持 / 失败。 */
sealed interface ComicOpenResult {
    /** 成功，entries 是页标识列表。 */
    data class Success(val entries: List<String>) : ComicOpenResult

    /** 有密码保护，但还没提供密码。 */
    data object PasswordRequired : ComicOpenResult

    /** 提供了密码但校验失败。 */
    data object WrongPassword : ComicOpenResult

    /** 加密 PDF，但系统版本（< Android 15）不支持带密码打开。 */
    data object UnsupportedEncrypted : ComicOpenResult

    /** 文件损坏 / 不是有效漫画 / 没有图片。 */
    data object Failed : ComicOpenResult
}

/** 简化版扫描用数据结构。 */
data class ScannedComic(
    val path: String,
    val parentPath: String,
    val whitelistRoot: String,
    val displayName: String,
    val size: Long,
    val dateModified: Long,
    val imageCount: Int,
    val noMediaHidden: Boolean,
)

/** 给定根目录递归找 zip/cbz/pdf。IO 密集，调用方应放在 Dispatchers.IO。 */
fun collectComicsInFolder(
    folderPath: String,
    whitelistRoot: String,
    passwordProvider: (String) -> String? = { null },
): List<ScannedComic> {
    val folder = File(folderPath)
    if (!folder.exists() || !folder.isDirectory) return emptyList()
    val results = ArrayList<ScannedComic>()
    walkComicsRecursive(
        folder,
        whitelistRoot,
        noMediaHidden = false,
        passwordProvider = passwordProvider,
        out = results,
    )
    return results
}

private fun walkComicsRecursive(
    dir: File,
    whitelistRoot: String,
    noMediaHidden: Boolean,
    passwordProvider: (String) -> String?,
    out: MutableList<ScannedComic>,
) {
    val children = dir.listFiles() ?: return
    val hidden = noMediaHidden || children.any {
        it.name.equals(NOMEDIA_FILE, ignoreCase = true)
    }
    for (f in children) {
        when {
            f.isDirectory -> walkComicsRecursive(
                f,
                whitelistRoot,
                hidden,
                passwordProvider,
                out,
            )

            f.isFile && isComicArchive(f.name) -> {
                // 已保存过密码的加密漫画，扫描时直接用密码拿页数/封面
                val password = passwordProvider(f.absolutePath)
                val imageCount = if (isPdf(f.name)) {
                    PdfComicReader.pageCount(f.absolutePath, password)
                } else {
                    runCatching {
                        ComicArchiveReader(f.absolutePath).use { it.listImageEntries().size }
                    }.getOrDefault(0)
                }
                out += ScannedComic(
                    path = f.absolutePath,
                    parentPath = f.parentFile?.absolutePath ?: dir.absolutePath,
                    whitelistRoot = whitelistRoot,
                    displayName = f.name,
                    size = f.length(),
                    dateModified = f.lastModified(),
                    imageCount = imageCount,
                    noMediaHidden = hidden,
                )
            }
        }
    }
}

private const val NOMEDIA_FILE = ".nomedia"

private fun isComicArchive(name: String): Boolean {
    val ext = name.substringAfterLast(
        '.',
        "",
    ).lowercase()
    return ext == "zip" || ext == "cbz" || ext == "pdf"
}

/** 按文件后缀判断是不是 PDF 漫画。 */
internal fun isPdf(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() == "pdf"

/** 把 Room entity 转成 model。 */
internal fun ComicFileIndexEntity.toComicFile(coverCachePath: String?): ComicFile = ComicFile(
    path = path,
    parentPath = parentPath,
    whitelistRoot = whitelistRoot,
    displayName = displayName,
    size = size,
    dateModified = dateModified,
    imageCount = imageCount,
    coverCachePath = coverCachePath,
    formattedFileSize = Utils.formatFileSize(size),
    noMediaHidden = noMediaHidden,
)

internal fun ComicFile.toEntity(): ComicFileIndexEntity = ComicFileIndexEntity(
    path = path,
    parentPath = parentPath,
    whitelistRoot = whitelistRoot,
    displayName = displayName,
    size = size,
    dateModified = dateModified,
    imageCount = imageCount,
    noMediaHidden = noMediaHidden,
)

/** 封面缓存的并发清理（避免多个 zip 同时写同一个 cover 文件）。 */
private val coverMutex = Mutex()

internal suspend fun extractCoverLocked(
    context: Context,
    zipPath: String,
    imageEntries: List<ComicArchiveReader.ImageEntry>,
    password: String? = null,
): String? = withContext(Dispatchers.IO) {
    coverMutex.withLock {
        ComicArchiveReader.extractCover(
            context,
            zipPath,
            imageEntries,
            password,
        )
    }
}
