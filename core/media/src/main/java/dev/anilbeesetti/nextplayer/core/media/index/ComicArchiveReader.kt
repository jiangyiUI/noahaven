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

        /** UI 层用：拿 zip 内部图片 entry 名列表。 */
        suspend fun listImageEntryNames(zipPath: String): List<String> =
            withContext(Dispatchers.IO) {
                ComicArchiveReader(zipPath).use { it.listImageEntries().map { e -> e.entryName } }
            }

        /** UI 层用：解 zip 内部指定 entry 的图片到 Bitmap。 */
        suspend fun decodeEntryBitmap(zipPath: String, entryName: String): Bitmap? =
            withContext(Dispatchers.IO) {
                runCatching {
                    ZipFile(zipPath).use { zip ->
                        zip.getInputStream(zip.getFileHeader(entryName)).use {
                            BitmapFactory.decodeStream(it)
                        }
                    }
                }.getOrNull()
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

        /** 扫描时用：解 zip 的第一张图片到缓存文件，返回缓存路径（或 null 失败）。 */
        fun extractCover(
            context: Context,
            zipPath: String,
            imageEntries: List<ImageEntry>,
        ): String? {
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
                    reader.openEntryStream(first)?.use { stream ->
                        coverFile.outputStream().use { out -> stream.copyTo(out) }
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

/** 给定根目录递归找 zip/cbz。IO 密集，调用方应放在 Dispatchers.IO。 */
fun collectComicsInFolder(folderPath: String, whitelistRoot: String): List<ScannedComic> {
    val folder = File(folderPath)
    if (!folder.exists() || !folder.isDirectory) return emptyList()
    val results = ArrayList<ScannedComic>()
    walkComicsRecursive(
        folder,
        whitelistRoot,
        noMediaHidden = false,
        results,
    )
    return results
}

private fun walkComicsRecursive(
    dir: File,
    whitelistRoot: String,
    noMediaHidden: Boolean,
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
                out,
            )

            f.isFile && isComicArchive(f.name) -> {
                val imageCount = runCatching {
                    ComicArchiveReader(f.absolutePath).use { it.listImageEntries().size }
                }.getOrDefault(0)
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
    return ext == "zip" || ext == "cbz"
}

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
): String? = withContext(Dispatchers.IO) {
    coverMutex.withLock {
        ComicArchiveReader.extractCover(
            context,
            zipPath,
            imageEntries,
        )
    }
}
