package dev.anilbeesetti.nextplayer.settings.screens.reader

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import dev.anilbeesetti.nextplayer.feature.books.data.BooksPreferences
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderCustomFont
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderPageTurn
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderProgressScope
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderSettings
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

/**
 * 主设置里的「阅读设置」页。读写的是 feature.books 里的 BooksPreferences 单例，
 * 因此与阅读页面板共用同一份 ReaderSettings：任一处修改，另一处实时同步。
 */
@KoinViewModel
class ReaderPreferencesViewModel(
    private val booksPreferences: BooksPreferences,
    @InjectedParam internal var output: Output,
) : MviViewModel<ReaderPreferencesUiState, ReaderPreferencesUiEvent>() {

    data class Output(val navigateUp: () -> Unit)

    private val stateInternal = MutableStateFlow(
        value = ReaderPreferencesUiState(
            settings = booksPreferences.settings.value,
            customFonts = booksPreferences.customFonts.value,
            customThemes = booksPreferences.customThemes.value,
        ),
    )
    override val state: StateFlow<ReaderPreferencesUiState> = stateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            booksPreferences.settings.collect { settings ->
                stateInternal.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            booksPreferences.customFonts.collect { fonts ->
                stateInternal.update { it.copy(customFonts = fonts) }
            }
        }
        viewModelScope.launch {
            booksPreferences.customThemes.collect { themes ->
                stateInternal.update { it.copy(customThemes = themes) }
            }
        }
    }

    override fun onAction(action: ReaderPreferencesUiEvent) {
        when (action) {
            ReaderPreferencesUiEvent.NavigateUp -> output.navigateUp()

            is ReaderPreferencesUiEvent.ShowDialog -> showDialog(action.value)

            is ReaderPreferencesUiEvent.UpdateFontSize ->
                updateSettings { it.copy(fontSizeSp = action.value) }

            is ReaderPreferencesUiEvent.UpdateLineSpacing ->
                updateSettings { it.copy(lineSpacing = action.value) }

            is ReaderPreferencesUiEvent.UpdateParagraphSpacing ->
                updateSettings { it.copy(paragraphSpacing = action.value) }

            is ReaderPreferencesUiEvent.UpdateHorizontalPadding ->
                updateSettings { it.copy(horizontalPaddingDp = action.value) }

            is ReaderPreferencesUiEvent.UpdateBrightness ->
                updateSettings { it.copy(brightness = action.value) }

            is ReaderPreferencesUiEvent.UpdateFontWeight ->
                updateSettings { it.copy(fontWeight = action.value) }

            is ReaderPreferencesUiEvent.SetProgressScope ->
                updateSettings { it.copy(progressScope = action.value) }

            is ReaderPreferencesUiEvent.SetLightTheme ->
                updateSettings { it.copy(lightTheme = action.value) }

            is ReaderPreferencesUiEvent.SetDarkTheme ->
                updateSettings { it.copy(darkTheme = action.value) }

            is ReaderPreferencesUiEvent.SetPageTurn ->
                updateSettings { it.copy(pageTurn = action.value) }

            is ReaderPreferencesUiEvent.SetFont ->
                updateSettings { it.copy(customFontPath = action.path) }

            ReaderPreferencesUiEvent.ToggleFirstLineIndent ->
                updateSettings { it.copy(firstLineIndent = !it.firstLineIndent) }

            ReaderPreferencesUiEvent.ToggleShowChapterTitle ->
                updateSettings { it.copy(showChapterTitle = !it.showChapterTitle) }

            ReaderPreferencesUiEvent.ToggleShowTimeBattery ->
                updateSettings { it.copy(showTimeBattery = !it.showTimeBattery) }

            ReaderPreferencesUiEvent.ToggleJustifyText ->
                updateSettings { it.copy(justifyText = !it.justifyText) }

            ReaderPreferencesUiEvent.ToggleOneHandMode ->
                updateSettings { it.copy(oneHandMode = !it.oneHandMode) }

            is ReaderPreferencesUiEvent.ImportFont -> importFont(action.uri)

            is ReaderPreferencesUiEvent.DeleteFont -> booksPreferences.deleteFont(action.path)

            is ReaderPreferencesUiEvent.SaveCustomTheme -> saveCustomTheme(action.theme)

            is ReaderPreferencesUiEvent.DeleteCustomTheme ->
                booksPreferences.deleteCustomTheme(action.storageKey)

            is ReaderPreferencesUiEvent.PickThemeImage -> pickThemeImage(action.uri)

            ReaderPreferencesUiEvent.ClearPendingThemeImage ->
                stateInternal.update { it.copy(pendingThemeImagePath = null) }
        }
    }

    private fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        booksPreferences.updateSettings(transform)
    }

    private fun showDialog(value: ReaderPreferenceDialog?) {
        stateInternal.update { it.copy(showDialog = value) }
    }

    private fun importFont(uri: Uri) {
        viewModelScope.launch {
            val path = booksPreferences.importFont(uri)
            if (path != null) {
                booksPreferences.updateSettings { it.copy(customFontPath = path) }
            }
        }
    }

    private fun saveCustomTheme(theme: ReaderTheme.Custom) {
        booksPreferences.saveCustomTheme(theme)
        booksPreferences.updateSettings { current ->
            if (theme.isDark) current.copy(darkTheme = theme) else current.copy(lightTheme = theme)
        }
    }

    private fun pickThemeImage(uri: Uri) {
        viewModelScope.launch {
            val path = booksPreferences.importBackgroundImage(uri) ?: return@launch
            stateInternal.update { it.copy(pendingThemeImagePath = path) }
        }
    }
}

@Stable
data class ReaderPreferencesUiState(
    val settings: ReaderSettings = ReaderSettings(),
    val customFonts: List<ReaderCustomFont> = emptyList(),
    val customThemes: List<ReaderTheme.Custom> = emptyList(),
    val pendingThemeImagePath: String? = null,
    val showDialog: ReaderPreferenceDialog? = null,
)

sealed interface ReaderPreferenceDialog {
    data object ProgressScope : ReaderPreferenceDialog
    data object LightTheme : ReaderPreferenceDialog
    data object DarkTheme : ReaderPreferenceDialog
    data object PageTurn : ReaderPreferenceDialog
    data object FontFamily : ReaderPreferenceDialog
}

sealed interface ReaderPreferencesUiEvent {
    data object NavigateUp : ReaderPreferencesUiEvent
    data class ShowDialog(val value: ReaderPreferenceDialog?) : ReaderPreferencesUiEvent
    data class UpdateFontSize(val value: Float) : ReaderPreferencesUiEvent
    data class UpdateLineSpacing(val value: Float) : ReaderPreferencesUiEvent
    data class UpdateParagraphSpacing(val value: Float) : ReaderPreferencesUiEvent
    data class UpdateHorizontalPadding(val value: Float) : ReaderPreferencesUiEvent
    data class UpdateBrightness(val value: Float) : ReaderPreferencesUiEvent
    data class UpdateFontWeight(val value: Int) : ReaderPreferencesUiEvent
    data class SetProgressScope(val value: ReaderProgressScope) : ReaderPreferencesUiEvent
    data class SetLightTheme(val value: ReaderTheme) : ReaderPreferencesUiEvent
    data class SetDarkTheme(val value: ReaderTheme) : ReaderPreferencesUiEvent
    data class SetPageTurn(val value: ReaderPageTurn) : ReaderPreferencesUiEvent
    data class SetFont(val path: String?) : ReaderPreferencesUiEvent
    data object ToggleFirstLineIndent : ReaderPreferencesUiEvent
    data object ToggleShowChapterTitle : ReaderPreferencesUiEvent
    data object ToggleShowTimeBattery : ReaderPreferencesUiEvent
    data object ToggleJustifyText : ReaderPreferencesUiEvent
    data object ToggleOneHandMode : ReaderPreferencesUiEvent
    data class ImportFont(val uri: Uri) : ReaderPreferencesUiEvent
    data class DeleteFont(val path: String) : ReaderPreferencesUiEvent
    data class SaveCustomTheme(val theme: ReaderTheme.Custom) : ReaderPreferencesUiEvent
    data class DeleteCustomTheme(val storageKey: String) : ReaderPreferencesUiEvent
    data class PickThemeImage(val uri: Uri) : ReaderPreferencesUiEvent
    data object ClearPendingThemeImage : ReaderPreferencesUiEvent
}
