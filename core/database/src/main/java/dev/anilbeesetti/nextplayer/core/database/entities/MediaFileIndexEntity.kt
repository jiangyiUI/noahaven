package dev.anilbeesetti.nextplayer.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 文件系统扫描结果的持久化缓存。
 *
 * 每一行代表磁盘上的一个视频文件，键是它的绝对路径。时长/分辨率是「额外元数据」，
 * 首次发现文件时先写 0，后台再用 [android.media.MediaMetadataRetriever] 补齐，
 * 这样冷启动只需要读这张表就能立刻显示媒体库，而不必重新遍历磁盘。
 */
@Entity(tableName = "media_file_index")
data class MediaFileIndexEntity(
    @PrimaryKey
    @ColumnInfo(name = "path")
    val path: String,
    @ColumnInfo(name = "parent_path")
    val parentPath: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "size")
    val size: Long,
    @ColumnInfo(name = "date_modified")
    val dateModified: Long,
    @ColumnInfo(name = "duration")
    val duration: Long = 0,
    @ColumnInfo(name = "width")
    val width: Int = 0,
    @ColumnInfo(name = "height")
    val height: Int = 0,
    @ColumnInfo(name = "metadata_loaded")
    val metadataLoaded: Boolean = false,
    @ColumnInfo(name = "no_media_hidden")
    val noMediaHidden: Boolean = false,
)
