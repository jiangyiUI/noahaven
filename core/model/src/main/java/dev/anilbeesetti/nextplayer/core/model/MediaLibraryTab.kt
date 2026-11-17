package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/** 「图片」页顶部的媒体库 tab。 */
@Serializable
enum class MediaLibraryTab {
    IMAGES,
    COMICS,
    ;

    companion object {
        val defaultOrder: List<MediaLibraryTab> = listOf(IMAGES, COMICS)

        /** 去掉未知/重复项并补齐缺失项，保证顺序列表始终完整可用。 */
        fun sanitize(order: List<MediaLibraryTab>): List<MediaLibraryTab> {
            val distinct = order.distinct().filter { it in entries }
            return distinct + entries.filter { it !in distinct }
        }
    }
}
