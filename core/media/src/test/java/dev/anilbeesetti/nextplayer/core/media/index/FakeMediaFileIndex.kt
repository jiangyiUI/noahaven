package dev.anilbeesetti.nextplayer.core.media.index

import kotlinx.coroutines.flow.MutableStateFlow

class FakeMediaFileIndex(
    initialEntries: List<MediaIndexEntry>? = emptyList(),
) : MediaFileIndex {

    override val entries: MutableStateFlow<List<MediaIndexEntry>?> =
        MutableStateFlow(initialEntries)

    var requestedRefreshPaths: MutableList<String?> = mutableListOf()

    override fun requestRefresh(folderPath: String?) {
        requestedRefreshPaths.add(folderPath)
    }

    override fun removePaths(paths: Collection<String>) {
        entries.value = entries.value?.filterNot { entry -> entry.path in paths }
    }

    override suspend fun find(path: String): MediaIndexEntry? =
        entries.value?.firstOrNull { it.path == path }
}
