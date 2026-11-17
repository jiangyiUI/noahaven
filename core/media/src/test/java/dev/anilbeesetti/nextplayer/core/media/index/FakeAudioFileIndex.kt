package dev.anilbeesetti.nextplayer.core.media.index

import kotlinx.coroutines.flow.MutableStateFlow

class FakeAudioFileIndex(
    initialEntries: List<AudioIndexEntry>? = emptyList(),
) : AudioFileIndex {

    override val entries: MutableStateFlow<List<AudioIndexEntry>?> =
        MutableStateFlow(initialEntries)

    var requestedRefreshPaths: MutableList<String?> = mutableListOf()

    override fun requestRefresh(folderPath: String?, force: Boolean) {
        requestedRefreshPaths.add(folderPath)
    }

    override fun removePaths(paths: Collection<String>) {
        entries.value = entries.value?.filterNot { entry -> entry.path in paths }
    }

    override suspend fun find(path: String): AudioIndexEntry? =
        entries.value?.firstOrNull { it.path == path }
}
