package dev.anilbeesetti.nextplayer.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 书籍白名单扫描结果 + 阅读进度。每个 txt/epub 文件一行，按绝对路径作为主键，
 * 与图片/漫画库一致：不复制文件，重命名/移动后由书架重新扫描并迁移进度。
 */
@Entity(tableName = "book")
data class BookEntity(
    @PrimaryKey
    @ColumnInfo(name = "path")
    val path: String,
    @ColumnInfo(name = "parent_path")
    val parentPath: String,
    @ColumnInfo(name = "whitelist_root")
    val whitelistRoot: String,
    /** 带后缀的文件名。 */
    @ColumnInfo(name = "display_name")
    val displayName: String,
    /** 书架展示标题（EPUB 元数据标题或去掉后缀的文件名）。 */
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "author")
    val author: String,
    /** TXT / EPUB。 */
    @ColumnInfo(name = "format")
    val format: String,
    /** 封面缓存绝对路径，没有封面时为 null。 */
    @ColumnInfo(name = "cover_path")
    val coverPath: String?,
    @ColumnInfo(name = "file_size")
    val fileSize: Long,
    @ColumnInfo(name = "date_modified")
    val dateModified: Long,
    @ColumnInfo(name = "no_media_hidden", defaultValue = "0")
    val noMediaHidden: Boolean = false,
    @ColumnInfo(name = "chapter_index", defaultValue = "0")
    val chapterIndex: Int = 0,
    /** 章节内字符偏移（排版后坐标）。 */
    @ColumnInfo(name = "char_offset", defaultValue = "0")
    val charOffset: Int = 0,
    @ColumnInfo(name = "percent", defaultValue = "0")
    val percent: Float = 0f,
    @ColumnInfo(name = "last_opened_at", defaultValue = "0")
    val lastOpenedAt: Long = 0L,
    @ColumnInfo(name = "finished", defaultValue = "0")
    val finished: Boolean = false,
)
