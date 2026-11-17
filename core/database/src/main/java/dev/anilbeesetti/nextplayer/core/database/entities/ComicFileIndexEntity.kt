package dev.anilbeesetti.nextplayer.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 漫画压缩包白名单扫描结果。每个 zip/cbz 文件一行，内部图片列表按需解包。 */
@Entity(tableName = "comic_file_index")
data class ComicFileIndexEntity(
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
    /** zip 内部图片条目总数（扫描时一次性读完，避免后续翻页反复打开 zip）。 */
    @ColumnInfo(name = "image_count")
    val imageCount: Int,
    /** 该文件所在目录（或任意上级目录）里有 `.nomedia`。 */
    @ColumnInfo(name = "no_media_hidden", defaultValue = "0")
    val noMediaHidden: Boolean = false,
)
