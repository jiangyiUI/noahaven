package dev.anilbeesetti.nextplayer.feature.more.screens.more

import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.fake.FakeMediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.fake.FakePreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.index.ComicFileIndex
import dev.anilbeesetti.nextplayer.core.model.ComicFile
import dev.anilbeesetti.nextplayer.core.model.ComicProgress
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.ui.base.DataState
import dev.anilbeesetti.nextplayer.settings.screens.medialibrary.MediaLibraryPreferencesUiEvent
import dev.anilbeesetti.nextplayer.settings.screens.medialibrary.MediaLibraryPreferencesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MoreViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val history = MutableStateFlow(value = listOf(Video.sample))
    private val preferences = FakePreferencesRepository()
    private val removedHistory = mutableListOf<String>()
    private val repository = object : MediaRepository by FakeMediaRepository() {
        override fun observePlaybackHistory() = history
        override suspend fun clearPlaybackHistory() {
            history.value = emptyList()
        }

        override suspend fun removePlaybackHistory(uris: List<String>) {
            removedHistory += uris
            history.value = history.value.filterNot { it.uriString in uris }
        }
    }
    private lateinit var viewModel: MoreViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher = dispatcher)
        viewModel = MoreViewModel(
            mediaRepository = repository,
            preferencesRepository = preferences,
            comicFileIndex = FakeComicFileIndex(),
            output = defaultOutput(),
        )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `history and preferences update independently without a state subscriber`() =
        runTest(context = dispatcher) {
            runCurrent()
            val updatedVideo = Video.sample.copy(nameWithExtension = "Updated.mp4")
            history.value = listOf(element = updatedVideo)
            runCurrent()
            assertEquals(
                DataState.Success(value = listOf(element = updatedVideo)),
                viewModel.state.value.history,
            )

            preferences.updateApplicationPreferences { it.copy(showExtensionField = true) }
            runCurrent()
            assertEquals(
                DataState.Success(value = listOf(element = updatedVideo)),
                viewModel.state.value.history,
            )
            assertEquals(
                preferences.applicationPreferences.value,
                viewModel.state.value.preferences,
            )
        }

    @Test
    fun `preferences update before history emits its first value`() =
        runTest(context = dispatcher) {
            viewModel.viewModelScope.cancel()
            val pendingHistory = MutableSharedFlow<List<Video>>()
            viewModel = MoreViewModel(
                mediaRepository = object : MediaRepository by repository {
                    override fun observePlaybackHistory() = pendingHistory
                },
                preferencesRepository = preferences,
                comicFileIndex = FakeComicFileIndex(),
                output = defaultOutput(),
            )
            preferences.updateApplicationPreferences { it.copy(showExtensionField = true) }
            runCurrent()
            assertEquals(
                DataState.Loading,
                viewModel.state.value.history,
            )
            assertTrue(viewModel.state.value.preferences.showExtensionField)
        }

    @Test
    fun `observers are cancelled with the view model`() = runTest(context = dispatcher) {
        runCurrent()
        assertEquals(
            1,
            history.subscriptionCount.value,
        )

        viewModel.viewModelScope.cancel()
        runCurrent()
        assertEquals(
            0,
            history.subscriptionCount.value,
        )
        history.value = emptyList()
        preferences.updateApplicationPreferences { it.copy(showExtensionField = true) }
        runCurrent()
        assertEquals(
            DataState.Success(value = listOf(Video.sample)),
            viewModel.state.value.history,
        )
        assertFalse(viewModel.state.value.preferences.showExtensionField)
    }

    @Test
    fun `clear history action updates state without a state subscriber`() =
        runTest(context = dispatcher) {
            runCurrent()
            viewModel.onAction(action = MoreAction.ClearHistory)
            runCurrent()
            assertEquals(
                DataState.Success(value = emptyList<Video>()),
                viewModel.state.value.history,
            )
        }

    @Test
    fun `media library settings also clear history only when turning it off`() =
        runTest(context = dispatcher) {
            val settingsViewModel = MediaLibraryPreferencesViewModel(
                preferencesRepository = preferences,
                mediaRepository = repository,
                output = MediaLibraryPreferencesViewModel.Output(
                    navigateUp = {},
                    openFolders = {},
                    openAudioFolders = {},
                    openImageFolders = {},
                    openComicFolders = {},
                    openThumbnails = {},
                ),
            )
            try {
                runCurrent()
                settingsViewModel.onAction(
                    MediaLibraryPreferencesUiEvent.SetWatchHistoryEnabled(enabled = false),
                )
                runCurrent()
                assertTrue(preferences.applicationPreferences.value.isHistoryPaused)
                assertEquals(
                    emptyList<Video>(),
                    history.value,
                )

                history.value = listOf(Video.sample)
                settingsViewModel.onAction(
                    MediaLibraryPreferencesUiEvent.SetWatchHistoryEnabled(enabled = true),
                )
                runCurrent()
                assertFalse(preferences.applicationPreferences.value.isHistoryPaused)
                assertEquals(
                    listOf(Video.sample),
                    history.value,
                )
            } finally {
                settingsViewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `selected history entries are removed without touching files`() =
        runTest(context = dispatcher) {
            val first = Video.sample.copy(uriString = "content://video/1")
            val second = Video.sample.copy(uriString = "content://video/2")
            history.value = listOf(
                first,
                second,
            )
            runCurrent()

            viewModel.onAction(MoreAction.ToggleSelection(uriString = first.uriString))
            viewModel.onAction(MoreAction.ToggleSelection(uriString = second.uriString))
            runCurrent()
            assertEquals(
                setOf(
                    first.uriString,
                    second.uriString,
                ),
                viewModel.state.value.selectedUris,
            )

            viewModel.onAction(MoreAction.DeleteSelected)
            runCurrent()
            assertEquals(
                listOf(
                    first.uriString,
                    second.uriString,
                ),
                removedHistory,
            )
            assertTrue(viewModel.state.value.selectedUris.isEmpty())
            assertEquals(
                DataState.Success(value = emptyList<Video>()),
                viewModel.state.value.history,
            )
        }

    @Test
    fun `actions use the current route output`() {
        var oldOutputCalled = false
        val played = mutableListOf<String>()
        viewModel.output = defaultOutput(onPlayVideo = { oldOutputCalled = true })
        viewModel.output = defaultOutput(onPlayVideo = { played += it.uriString })

        viewModel.onAction(
            MoreAction.PlayVideo(
                video = Video.sample.copy(uriString = "content://video/1"),
            ),
        )

        assertEquals(
            listOf(element = "content://video/1"),
            played,
        )
        assertFalse(oldOutputCalled)
    }

    private fun defaultOutput(onPlayVideo: (Video) -> Unit = {}) = MoreViewModel.Output(
        playVideo = onPlayVideo,
        playUri = {},
        openComic = {},
        openSettings = {},
        openVault = {},
        openPlaylists = {},
        openNetwork = {},
    )
}

private class FakeComicFileIndex : ComicFileIndex {
    override val entries = MutableStateFlow<List<ComicFile>?>(value = null)

    override fun requestRefresh(
        whitelistRoots: Collection<String>,
        folderPath: String?,
        force: Boolean,
    ) = Unit

    override suspend fun find(path: String): ComicFile? = null

    override suspend fun getProgress(path: String): ComicProgress? = null

    override suspend fun saveProgress(progress: ComicProgress) = Unit

    override fun observeProgress(): Flow<List<ComicProgress>> = flowOf(emptyList())

    override suspend fun deleteProgress(path: String) = Unit

    override suspend fun moveProgress(fromPath: String, toPath: String) = Unit

    override suspend fun clearProgress() = Unit
}
