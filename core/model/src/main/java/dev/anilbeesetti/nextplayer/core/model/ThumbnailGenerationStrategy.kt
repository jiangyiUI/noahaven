package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThumbnailGenerationStrategy {
    FIRST_FRAME,
    FRAME_AT_PERCENTAGE,
    HYBRID,
}
