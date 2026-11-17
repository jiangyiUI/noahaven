package dev.anilbeesetti.nextplayer.core.media.index

import android.net.Uri
import dev.anilbeesetti.nextplayer.core.common.Utils
import dev.anilbeesetti.nextplayer.core.database.entities.ImageFileIndexEntity
import dev.anilbeesetti.nextplayer.core.model.ImageFile
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/** 只包含白名单目录的图片磁盘索引。 */
interface ImageFileIndex {

    /** null 表示数据库尚未载入；否则为当前完整快照。 */
    val entries: StateFlow<List<ImageFile>?>

    /** folderPath 为 null 时协调全部白名单，否则只扫描该目录所属的白名单子树。 */
    fun requestRefresh(
        whitelistRoots: Collection<String>,
        folderPath: String? = null,
        force: Boolean = false,
    )

    fun removePaths(paths: Collection<String>)

    /** 删除已移除根目录的索引，并保留仍被 retainedRoots 覆盖的嵌套目录图片。 */
    fun removeRoots(roots: Collection<String>, retainedRoots: Collection<String>)

    suspend fun find(path: String): ImageFile?
}

fun ImageFileIndexEntity.toImageFile(): ImageFile = ImageFile(
    path = path,
    parentPath = parentPath,
    whitelistRoot = whitelistRoot,
    uriString = Uri.fromFile(File(path)).toString(),
    nameWithExtension = displayName,
    size = size,
    dateModified = dateModified,
    width = width,
    height = height,
    noMediaHidden = noMediaHidden,
    dimensionsLoaded = dimensionsLoaded,
    formattedFileSize = Utils.formatFileSize(size = size),
)
