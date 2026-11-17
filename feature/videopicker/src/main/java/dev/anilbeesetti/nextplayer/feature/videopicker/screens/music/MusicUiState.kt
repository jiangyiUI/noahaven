package dev.anilbeesetti.nextplayer.feature.videopicker.screens.music

import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.AudioFile
import dev.anilbeesetti.nextplayer.core.model.Folder

data class MusicUiState(
    val folderPath: String? = null,
    val folderName: String? = null,
    val folders: List<Folder> = emptyList(),
    val audios: List<AudioFile> = emptyList(),
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    val isLoading: Boolean = false,
    val hasAllFilesAccess: Boolean = true,
    val refreshing: Boolean = false,
)
