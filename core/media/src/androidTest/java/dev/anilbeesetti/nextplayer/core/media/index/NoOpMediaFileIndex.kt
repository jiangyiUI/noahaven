package dev.anilbeesetti.nextplayer.core.media.index

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 只用于构造 [dev.anilbeesetti.nextplayer.core.media.services.LocalMediaOperationsService]。 */
class NoOpMediaFileIndex : MediaFileIndex {

    override val entries: StateFlow<List<MediaIndexEntry>?> = MutableStateFlow(value = emptyList())

    override fun requestRefresh(folderPath: String?) = Unit

    override fun removePaths(paths: Collection<String>) = Unit

    override suspend fun find(path: String): MediaIndexEntry? = null
}
