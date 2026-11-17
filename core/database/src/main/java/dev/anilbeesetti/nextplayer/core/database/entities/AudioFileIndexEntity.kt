package dev.anilbeesetti.nextplayer.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 音频磁盘扫描结果的持久化缓存，结构与视频的 media_file_index 一一对应，
 * 额外保存 artist/album/title 这几个音频标签。
 *
 * 首次发现文件时先写 stat 信息和空标签，后台再用 [android.media.MediaMetadataRetriever]
 * 逐个补齐，这样冷启动只读这张表就能立刻显示音频列表。
 */
@Entity(tableName = "audio_file_index")
data class AudioFileIndexEntity(
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
    @ColumnInfo(name = "artist")
    val artist: String = "",
    @ColumnInfo(name = "album")
    val album: String = "",
    @ColumnInfo(name = "title")
    val title: String = "",
    @ColumnInfo(name = "metadata_loaded")
    val metadataLoaded: Boolean = false,
    @ColumnInfo(name = "no_media_hidden")
    val noMediaHidden: Boolean = false,
)
