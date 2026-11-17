package dev.anilbeesetti.nextplayer.core.model

/** 单本漫画的阅读进度。方向按本漫画单独保存，不影响其它漫画。 */
data class ComicProgress(
    val path: String,
    val lastPage: Int,
    val readingDirection: ComicReadingDirection,
    /** 最近一次阅读时间，用于「更多」页历史记录按时间排序。 */
    val updatedAt: Long = 0L,
)
