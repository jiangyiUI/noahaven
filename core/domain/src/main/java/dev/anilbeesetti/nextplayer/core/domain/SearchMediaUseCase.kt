package dev.anilbeesetti.nextplayer.core.domain

import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.Video
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

data class SearchResults(
    val folders: List<Folder> = emptyList(),
    val videos: List<Video> = emptyList(),
) {
    val isEmpty: Boolean
        get() = folders.isEmpty() && videos.isEmpty()

    val totalCount: Int
        get() = folders.size + videos.size
}

@Factory
class SearchMediaUseCase(
    private val getSortedVideosUseCase: GetSortedVideosUseCase,
    private val getSortedFoldersUseCase: GetSortedFoldersUseCase,
    @Named(DiQualifiers.DEFAULT_DISPATCHER) private val defaultDispatcher: CoroutineDispatcher,
) {

    operator fun invoke(query: String): Flow<SearchResults> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return flowOf(value = SearchResults())
        }

        return combine(
            flow = getSortedVideosUseCase(),
            flow2 = getSortedFoldersUseCase(),
        ) { videos, folders ->
            val searchMatcher = SearchMatcher(query = normalizedQuery)

            // Score and filter folders
            val scoredFolders = folders.mapNotNull { folder ->
                val score = searchMatcher.calculateScore(
                    folder.name,
                    folder.path,
                )
                if (score > 0) folder to score else null
            }.sortedByDescending { it.second }.map { it.first }

            val scoredVideos = videos.mapNotNull { video ->
                val score = searchMatcher.calculateScore(
                    video.nameWithExtension,
                    video.path,
                )
                if (score > 0) video to score else null
            }.sortedByDescending { it.second }.map { it.first }

            SearchResults(
                folders = scoredFolders,
                videos = scoredVideos,
            )
        }.flowOn(context = defaultDispatcher)
    }
}
