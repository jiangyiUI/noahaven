package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ImageLayoutMode {
    LIST,
    GRID,

    /** 一行三列，只显示缩略图 */
    NINE_GRID,
}
