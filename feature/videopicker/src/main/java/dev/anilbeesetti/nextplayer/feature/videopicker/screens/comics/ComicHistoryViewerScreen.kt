package dev.anilbeesetti.nextplayer.feature.videopicker.screens.comics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.index.ComicArchiveReader
import dev.anilbeesetti.nextplayer.core.media.index.ComicFileIndex
import dev.anilbeesetti.nextplayer.core.media.index.ComicOpenResult
import dev.anilbeesetti.nextplayer.core.model.ComicProgress
import dev.anilbeesetti.nextplayer.core.model.ComicReadingDirection
import dev.anilbeesetti.nextplayer.core.ui.components.BindTopLevelBottomBarVisible
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.ComicPasswordDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.ComicPasswordUnsupportedDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

/**
 * 从「更多」页历史记录直接打开某本漫画。与漫画库里的查看器共用 [ComicViewer]，
 * 进度、翻页方向、最近阅读记录都按同一套规则保存。
 */
@KoinViewModel
class ComicHistoryViewerViewModel(
    private val comicFileIndex: ComicFileIndex,
    private val preferencesRepository: PreferencesRepository,
    @InjectedParam private val path: String,
) : ViewModel() {

    data class UiState(
        val path: String = "",
        val loading: Boolean = true,
        val entries: List<String> = emptyList(),
        val initialIndex: Int = 0,
        val direction: ComicReadingDirection = ComicReadingDirection.RIGHT_TO_LEFT,
        val password: String? = null,
        /** 加密漫画等待用户输入密码。 */
        val needsPassword: Boolean = false,
        /** 上一次输入的密码错误。 */
        val wrongPassword: Boolean = false,
        /** 加密 PDF 但系统版本不支持。 */
        val unsupported: Boolean = false,
    )

    private val stateInternal = MutableStateFlow(UiState(path = path))
    val state: StateFlow<UiState> = stateInternal.asStateFlow()

    private var lastPage: Int = 0

    init {
        viewModelScope.launch {
            val saved = comicFileIndex.resolvePassword(path)
            openWith(password = saved, isUserInput = false)
        }
    }

    /** 用户提交密码。 */
    fun submitPassword(password: String) {
        viewModelScope.launch { openWith(password = password, isUserInput = true) }
    }

    private suspend fun openWith(password: String?, isUserInput: Boolean) {
        when (val result = ComicArchiveReader.openComic(path, password)) {
            is ComicOpenResult.Success -> {
                if (isUserInput && password != null) comicFileIndex.savePassword(path, password)
                val progress = comicFileIndex.getProgress(path)
                lastPage = progress?.lastPage
                    ?.coerceIn(0, maxOf(result.entries.lastIndex, 0)) ?: 0
                stateInternal.update {
                    it.copy(
                        loading = false,
                        entries = result.entries,
                        initialIndex = lastPage,
                        direction = progress?.readingDirection
                            ?: ComicReadingDirection.RIGHT_TO_LEFT,
                        password = password,
                        needsPassword = false,
                        wrongPassword = false,
                    )
                }
                preferencesRepository.updateApplicationPreferences { preferences ->
                    preferences.copy(lastViewedComicPath = path)
                }
            }

            ComicOpenResult.PasswordRequired -> stateInternal.update {
                it.copy(loading = false, needsPassword = true, wrongPassword = false)
            }

            ComicOpenResult.WrongPassword -> {
                // 失效的旧密码直接清掉
                if (!isUserInput && password != null) comicFileIndex.deletePassword(path)
                stateInternal.update {
                    it.copy(
                        loading = false,
                        needsPassword = true,
                        wrongPassword = isUserInput,
                    )
                }
            }

            ComicOpenResult.UnsupportedEncrypted -> stateInternal.update {
                it.copy(loading = false, unsupported = true)
            }

            ComicOpenResult.Failed -> stateInternal.update { it.copy(loading = false) }
        }
    }

    fun onProgressChanged(page: Int, direction: ComicReadingDirection) {
        lastPage = page
        saveProgress(page = page, direction = direction)
    }

    fun onDirectionChange(direction: ComicReadingDirection) {
        stateInternal.update { it.copy(direction = direction) }
        saveProgress(page = lastPage, direction = direction)
    }

    private fun saveProgress(page: Int, direction: ComicReadingDirection) {
        viewModelScope.launch {
            comicFileIndex.saveProgress(
                ComicProgress(
                    path = path,
                    lastPage = page,
                    readingDirection = direction,
                ),
            )
        }
    }
}

@Composable
fun ComicHistoryViewerScreen(
    viewModel: ComicHistoryViewerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BindTopLevelBottomBarVisible(visible = false)

    // 只有真正打不开（损坏/无图片）才退回；等密码、系统不支持都停留在本页
    LaunchedEffect(state.loading, state.entries.isEmpty(), state.needsPassword, state.unsupported) {
        if (!state.loading && state.entries.isEmpty() && !state.needsPassword && !state.unsupported) {
            onBack()
        }
    }

    if (state.loading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    } else if (state.entries.isNotEmpty()) {
        ComicViewer(
            images = ComicImageList(
                zipPath = state.path,
                entries = state.entries,
                readingDirection = state.direction,
                password = state.password,
            ),
            initialIndex = state.initialIndex,
            readingDirection = state.direction,
            onProgressChanged = viewModel::onProgressChanged,
            onDirectionChange = viewModel::onDirectionChange,
            onBack = onBack,
        )
    }

    if (state.needsPassword) {
        ComicPasswordDialog(
            fileName = state.path.substringAfterLast('/'),
            wrongPassword = state.wrongPassword,
            onDismiss = onBack,
            onSubmit = viewModel::submitPassword,
        )
    }

    if (state.unsupported) {
        ComicPasswordUnsupportedDialog(onDismiss = onBack)
    }
}
