package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/** 书籍页顶部的 tab：书架 / 浏览。 */
@Serializable
enum class BookLibraryTab {
    SHELF,
    BROWSE,
    ;

    companion object {
        val defaultOrder: List<BookLibraryTab> = listOf(SHELF, BROWSE)

        /** 去掉未知/重复项并补齐缺失项，保证顺序列表始终完整可用。 */
        fun sanitize(order: List<BookLibraryTab>): List<BookLibraryTab> {
            val distinct = order.distinct().filter { it in entries }
            return distinct + entries.filter { it !in distinct }
        }
    }
}
