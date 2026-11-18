package dev.anilbeesetti.nextplayer.core.media.services

import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.annotation.ChecksSdkIntAtLeast
import java.io.File
import kotlinx.coroutines.flow.Flow

interface MediaOperationsService {
    fun initialize(activity: ComponentActivity)
    suspend fun deleteMedia(uris: List<Uri>, permanently: Boolean = false): Boolean
    suspend fun restoreMedia(uris: List<Uri>): Boolean
    suspend fun renameMedia(uri: Uri, to: String): Boolean
    suspend fun shareMedia(uris: List<Uri>, mimeType: String = "video/*")
    suspend fun moveMedia(targets: Map<Uri, File>): Map<Uri, File?>

    fun transferMedia(uris: List<Uri>, folderUri: Uri, mode: TransferMode): Flow<TransferEvent>

    companion object {
        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
        fun willSystemAsksForDeleteConfirmation(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
        fun supportsTrash(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }
}

enum class TransferMode { COPY, MOVE }

sealed interface TransferEvent {
    data class Progress(val progress: TransferProgress) : TransferEvent

    /**
     * @param movedFiles 仅 MOVE 时位置真的变了的文件（旧 uri → 新路径），
     * 调用方据此把播放进度和历史记录迁移到新位置。
     */
    data class Completed(
        val result: TransferResult,
        val movedFiles: List<MovedFile> = emptyList(),
    ) : TransferEvent
}

data class TransferProgress(
    val totalFiles: Int,
    val currentIndex: Int = 0,
    val currentName: String? = null,
    val currentBytesCopied: Long = 0,
    val currentBytesTotal: Long = 0,
    val overallBytesCopied: Long = 0,
    val overallBytesTotal: Long = 0,
) {
    val currentFraction: Float?
        get() = if (currentBytesTotal > 0) {
            (currentBytesCopied.toFloat() / currentBytesTotal).coerceIn(
                minimumValue = 0f,
                maximumValue = 1f,
            )
        } else {
            null
        }

    val overallFraction: Float
        get() = if (overallBytesTotal > 0) {
            (overallBytesCopied.toFloat() / overallBytesTotal).coerceIn(
                minimumValue = 0f,
                maximumValue = 1f,
            )
        } else if (totalFiles > 0) {
            (currentIndex.toFloat() / totalFiles).coerceIn(
                minimumValue = 0f,
                maximumValue = 1f,
            )
        } else {
            0f
        }
}

data class TransferResult(
    val succeeded: Int,
    val failed: Int,
    val sameFolderSkipped: Int = 0,
    val originalsNotDeleted: Boolean = false,
)

/** 一次移动里换了位置的文件：旧 uri 与磁盘上的新路径。 */
data class MovedFile(val sourceUri: Uri, val destinationPath: String)

/**
 * 重命名输入框只显示不带后缀的名字，用户把后缀删掉时补回原后缀，
 * 避免文件变成无扩展名；已经带相同后缀（不分大小写）则原样返回。
 */
fun String.withPreservedExtension(originalName: String): String {
    val extension = originalName.substringAfterLast('.', missingDelimiterValue = "")
    if (extension.isEmpty()) return this
    return if (endsWith(".$extension", ignoreCase = true)) this else "$this.$extension"
}
