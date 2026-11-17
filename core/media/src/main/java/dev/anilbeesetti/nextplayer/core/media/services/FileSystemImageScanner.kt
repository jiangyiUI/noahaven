package dev.anilbeesetti.nextplayer.core.media.services

import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import java.io.File

private val IMAGE_EXTENSIONS = setOf(
    "jpg",
    "jpeg",
    "png",
    "webp",
    "gif",
    "bmp",
    "heic",
    "heif",
    "avif",
    "jxl",
)

/** 只遍历明确指定的白名单目录，扫描阶段只读 stat，不读取图片内容。 */
class FileSystemImageScanner {

    data class ScannedImage(
        val path: String,
        val parentPath: String,
        val whitelistRoot: String,
        val displayName: String,
        val size: Long,
        val dateModified: Long,
        val noMediaHidden: Boolean,
    )

    suspend fun scan(
        folderPath: String,
        whitelistRoot: String,
        onBatch: suspend (List<ScannedImage>) -> Unit,
    ): Boolean {
        val root = File(folderPath)
        if (!root.exists() || !root.isDirectory) return true

        val batch = ArrayList<ScannedImage>(BATCH_SIZE)
        var lastFlushAt = System.currentTimeMillis()
        suspend fun flush() {
            if (batch.isEmpty()) return
            onBatch(batch.toList())
            batch.clear()
            lastFlushAt = System.currentTimeMillis()
        }

        val complete = walk(
            directory = root,
            whitelistRoot = whitelistRoot,
            noMediaHidden = false,
            onFile = { image ->
                batch.add(image)
                if (batch.size >= BATCH_SIZE ||
                    System.currentTimeMillis() - lastFlushAt >= FLUSH_INTERVAL_MS
                ) {
                    flush()
                }
            },
        )
        flush()
        return complete
    }

    private suspend fun walk(
        directory: File,
        whitelistRoot: String,
        noMediaHidden: Boolean,
        onFile: suspend (ScannedImage) -> Unit,
    ): Boolean {
        val children = directory.listFiles() ?: return false
        val hidden =
            noMediaHidden || children.any {
                it.name.equals(
                    NOMEDIA_FILE,
                    ignoreCase = true,
                )
            }
        var complete = true
        for (child in children) {
            val name = child.name
            val stat = child.statInfo()
            if (stat == null) {
                complete = false
                continue
            }
            if (stat.isDirectory) {
                if (name.startsWith(".") || name.equals(
                        "Android",
                        ignoreCase = true,
                    )
                ) {
                    continue
                }
                if (!walk(
                        child,
                        whitelistRoot,
                        hidden,
                        onFile,
                    )
                ) {
                    complete = false
                }
            } else {
                if (name.startsWith(".") || !name.hasImageExtension()) continue
                onFile(
                    ScannedImage(
                        path = child.path,
                        parentPath = directory.path,
                        whitelistRoot = whitelistRoot,
                        displayName = name,
                        size = stat.size,
                        dateModified = stat.modifiedSeconds,
                        noMediaHidden = hidden,
                    ),
                )
            }
        }
        return complete
    }

    private data class StatInfo(
        val isDirectory: Boolean,
        val size: Long,
        val modifiedSeconds: Long,
    )

    private fun File.statInfo(): StatInfo? = try {
        val stat = Os.stat(path)
        if (stat.st_mode == 0) {
            fileApiStat()
        } else {
            StatInfo(
                isDirectory = OsConstants.S_ISDIR(stat.st_mode),
                size = stat.st_size,
                modifiedSeconds = stat.st_mtime,
            )
        }
    } catch (_: Throwable) {
        fileApiStat()
    }

    private fun File.fileApiStat(): StatInfo? {
        if (!exists()) return null
        return StatInfo(
            isDirectory = isDirectory,
            size = length(),
            modifiedSeconds = lastModified() / 1000,
        )
    }

    private fun String.hasImageExtension(): Boolean {
        val extension = substringAfterLast(
            '.',
            missingDelimiterValue = "",
        ).lowercase()
        if (extension.isEmpty()) return false
        if (extension in IMAGE_EXTENSIONS) return true
        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?.startsWith("image/") == true
    }

    private companion object {
        const val NOMEDIA_FILE = ".nomedia"
        const val BATCH_SIZE = 200
        const val FLUSH_INTERVAL_MS = 300L
    }
}

/** 只解码图片边界，不创建位图；失败也返回已处理，避免每次启动重复读取坏文件。 */
fun readImageDimensions(path: String): Pair<Int, Int> {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    return runCatching {
        BitmapFactory.decodeFile(
            path,
            options,
        )
        options.outWidth.coerceAtLeast(0) to options.outHeight.coerceAtLeast(0)
    }.getOrDefault(0 to 0)
}
