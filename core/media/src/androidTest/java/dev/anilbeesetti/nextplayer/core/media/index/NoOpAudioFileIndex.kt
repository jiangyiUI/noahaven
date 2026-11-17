package dev.anilbeesetti.nextplayer.core.media.index

import dev.anilbeesetti.nextplayer.core.model.ImageFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 只用于构造 [dev.anilbeesetti.nextplayer.core.media.services.LocalMediaOperationsService]。 */
class NoOpAudioFileIndex : AudioFileIndex {

    override val entries: StateFlow<List<AudioIndexEntry>?> = MutableStateFlow(value = emptyList())

    override fun requestRefresh(folderPath: String?, force: Boolean) = Unit

    override fun removePaths(paths: Collection<String>) = Unit

    override suspend fun find(path: String): AudioIndexEntry? = null
}

class NoOpImageFileIndex : ImageFileIndex {

    override val entries: StateFlow<List<ImageFile>?> = MutableStateFlow(value = emptyList())

    override fun requestRefresh(
        whitelistRoots: Collection<String>,
        folderPath: String?,
        force: Boolean,
    ) = Unit

    override fun removePaths(paths: Collection<String>) = Unit

    override fun removeRoots(roots: Collection<String>, retainedRoots: Collection<String>) = Unit

    override suspend fun find(path: String): ImageFile? = null
}
