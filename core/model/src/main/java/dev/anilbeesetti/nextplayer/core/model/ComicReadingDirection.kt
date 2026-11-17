package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ComicReadingDirection {
    /** 页序从左到右，向左滑看下一页（欧美漫画、中文大陆版）。 */
    LEFT_TO_RIGHT,

    /** 页序从右到左，向右滑看下一页（日漫传统阅读方向）。 */
    RIGHT_TO_LEFT,
}
