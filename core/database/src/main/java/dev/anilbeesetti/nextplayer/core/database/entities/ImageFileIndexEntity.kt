package dev.anilbeesetti.nextplayer.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 图片白名单扫描结果的持久化缓存；分辨率由后台 bounds 解码分批补齐。 */
@Entity(tableName = "image_file_index")
data class ImageFileIndexEntity(
    @PrimaryKey
    @ColumnInfo(name = "path")
    val path: String,
    @ColumnInfo(name = "parent_path")
    val parentPath: String,
    @ColumnInfo(name = "whitelist_root")
    val whitelistRoot: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "size")
    val size: Long,
    @ColumnInfo(name = "date_modified")
    val dateModified: Long,
    @ColumnInfo(name = "no_media_hidden")
    val noMediaHidden: Boolean,
    @ColumnInfo(name = "width")
    val width: Int = 0,
    @ColumnInfo(name = "height")
    val height: Int = 0,
    @ColumnInfo(name = "dimensions_loaded")
    val dimensionsLoaded: Boolean = false,
)
