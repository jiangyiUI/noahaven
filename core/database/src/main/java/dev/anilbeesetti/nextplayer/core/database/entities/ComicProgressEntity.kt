package dev.anilbeesetti.nextplayer.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 单本漫画的阅读进度：上次看到第几页、这本漫画自己的翻页方向。 */
@Entity(tableName = "comic_progress")
data class ComicProgressEntity(
    @PrimaryKey
    @ColumnInfo(name = "path")
    val path: String,
    @ColumnInfo(name = "last_page")
    val lastPage: Int,
    @ColumnInfo(name = "reading_direction")
    val readingDirection: String,
    /** 最近一次阅读时间（毫秒），历史记录按它倒序。 */
    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = 0L,
    /** 漫画是否已看完（阅读进度达到看过阈值）。双向标记：低于阈值自动取消。 */
    @ColumnInfo(name = "watched", defaultValue = "0")
    val watched: Boolean = false,
)
