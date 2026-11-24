package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class MediaViewMode {
    FOLDER_TREE,
    FOLDERS,
    VIDEOS,
}
