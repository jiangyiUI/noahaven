package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * 电子书书签：挂在某本书的某一页上。
 * 存储在 [ApplicationPreferences.bookBookmarks] 里，按书籍绝对路径分组。
 *
 * @param id 创建时间戳（毫秒），同一本书内唯一，用于定位删除。
 * @param chapterIndex 创建时所在章节下标。
 * @param charOffset 创建时该页起始字符偏移，与阅读进度同一坐标系。
 * @param percent 创建时该书的整体阅读进度，0f..1f。
 * @param chapterTitle 创建时所在章节标题快照。
 * @param previewText 该页第一行文字预览。
 * @param createdAt 创建时间戳，用于按创建顺序展示。
 */
@Serializable
data class Bookmark(
    val id: Long,
    val chapterIndex: Int,
    val charOffset: Int,
    val percent: Float,
    val chapterTitle: String,
    val previewText: String,
    val createdAt: Long,
)
