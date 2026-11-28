package dev.anilbeesetti.nextplayer.settings.screens.general

import android.net.Uri
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.extensions.clearAllCache
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import dev.anilbeesetti.nextplayer.settings.backup.LegacyShelfImportService.LegacyShelfImportPreview
import dev.anilbeesetti.nextplayer.settings.backup.LegacyShelfImportService
import dev.anilbeesetti.nextplayer.settings.backup.ReadingProgressImportService
import dev.anilbeesetti.nextplayer.settings.backup.ReadingProgressImportService.ReadingProgressPreview
import dev.anilbeesetti.nextplayer.settings.backup.SettingsBackupService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class GeneralPreferencesViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val imageLoader: ImageLoader,
    private val settingsBackupService: SettingsBackupService,
    private val legacyShelfImportService: LegacyShelfImportService,
    private val readingProgressImportService: ReadingProgressImportService,
    @InjectedParam internal var output: Output,
) : MviViewModel<GeneralPreferencesUiState, GeneralPreferencesUiEvent>() {

    data class Output(val navigateUp: () -> Unit, val showMessage: (Int) -> Unit = {})

    private val stateInternal = MutableStateFlow(value = GeneralPreferencesUiState())
    override val state: StateFlow<GeneralPreferencesUiState> = stateInternal.asStateFlow()
    private var pendingImport: String? = null

    init {
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { prefs ->
                stateInternal.update {
                    it.copy(showLegacyShelfImport = prefs.showLegacyShelfImport)
                }
            }
        }
    }

    override fun onAction(action: GeneralPreferencesUiEvent) {
        when (action) {
            is GeneralPreferencesUiEvent.NavigateUp -> output.navigateUp()

            is GeneralPreferencesUiEvent.ShowDialog -> showDialog(action.value)

            is GeneralPreferencesUiEvent.ClearThumbnailCache -> clearThumbnailCache()

            is GeneralPreferencesUiEvent.ResetSettings -> resetSettings()

            is GeneralPreferencesUiEvent.ExportSettings -> exportSettings(
                action.uri,
                action.includeNetworkConnections,
            )

            is GeneralPreferencesUiEvent.PrepareImport -> prepareImport(action.uri)

            is GeneralPreferencesUiEvent.ImportSettings -> importSettings()

            is GeneralPreferencesUiEvent.PickLegacyFiles -> pickLegacyFiles(action.uris)

            is GeneralPreferencesUiEvent.ConfirmLegacyImport -> confirmLegacyImport()

            is GeneralPreferencesUiEvent.DismissLegacyPreview ->
                stateInternal.update { it.copy(legacyPreview = null) }

            is GeneralPreferencesUiEvent.ExportNotFound -> exportNotFound(action.uri)

            is GeneralPreferencesUiEvent.PickProgressBackup -> pickProgressBackup(action.uri)

            is GeneralPreferencesUiEvent.ConfirmProgressImport -> confirmProgressImport()

            is GeneralPreferencesUiEvent.DismissProgressPreview ->
                stateInternal.update { it.copy(progressPreview = null) }
        }
    }

    private fun showDialog(value: GeneralPreferencesDialog?) {
        if (value != GeneralPreferencesDialog.ImportSettingsDialog) pendingImport = null
        stateInternal.update { it.copy(showDialog = value) }
    }

    private fun clearThumbnailCache() {
        viewModelScope.launch { imageLoader.clearAllCache() }
    }

    private fun resetSettings() {
        viewModelScope.launch { preferencesRepository.resetPreferences() }
    }

    private fun exportSettings(uri: Uri, includeNetworkConnections: Boolean) {
        showDialog(null)
        backupOperation(R.string.settings_export_failed) {
            settingsBackupService.exportSettings(
                uri,
                includeNetworkConnections,
            )
            output.showMessage(R.string.settings_export_success)
        }
    }

    private fun prepareImport(uri: Uri) {
        backupOperation(R.string.settings_import_invalid) {
            pendingImport = settingsBackupService.readSettings(uri)
            showDialog(GeneralPreferencesDialog.ImportSettingsDialog)
        }
    }

    private fun importSettings() {
        val content = pendingImport ?: return
        showDialog(null)
        backupOperation(R.string.settings_import_failed) {
            settingsBackupService.importSettings(content)
            output.showMessage(R.string.settings_import_success)
        }
    }

    private fun pickLegacyFiles(uris: List<Uri>) {
        if (uris.size < 2) {
            output.showMessage(R.string.settings_import_invalid)
            return
        }
        val categoryUri = uris.firstOrNull {
            it.lastPathSegment?.contains("category", ignoreCase = true) == true
        } ?: uris[0]
        val bookUri = uris.firstOrNull {
            it.lastPathSegment?.contains("book", ignoreCase = true) == true
        } ?: uris[1]
        backupOperation(R.string.settings_import_invalid) {
            val preview = legacyShelfImportService.parseAndPreview(categoryUri, bookUri)
            stateInternal.update { it.copy(legacyPreview = preview) }
        }
    }

    private fun confirmLegacyImport() {
        val preview = stateInternal.value.legacyPreview ?: return
        backupOperation(R.string.settings_import_failed) {
            legacyShelfImportService.applyImport(preview)
            stateInternal.update { it.copy(legacyPreview = null) }
            output.showMessage(R.string.settings_import_success)
        }
    }

    private fun exportNotFound(uri: Uri) {
        val preview = stateInternal.value.legacyPreview ?: return
        backupOperation(R.string.settings_export_failed) {
            legacyShelfImportService.exportNotFound(preview, uri)
            output.showMessage(R.string.settings_export_success)
        }
    }

    private fun pickProgressBackup(uri: Uri) {
        backupOperation(R.string.settings_reading_progress_import_failed) {
            val preview = readingProgressImportService.readAndPreview(uri)
            if (preview == null) {
                output.showMessage(R.string.settings_reading_progress_import_failed)
                return@backupOperation
            }
            if (preview.totalWithProgress == 0) {
                output.showMessage(R.string.settings_reading_progress_import_empty)
                return@backupOperation
            }
            stateInternal.update { it.copy(progressPreview = preview) }
        }
    }

    private fun confirmProgressImport() {
        val preview = stateInternal.value.progressPreview ?: return
        backupOperation(R.string.settings_reading_progress_import_failed) {
            val written = readingProgressImportService.applyImport(preview)
            stateInternal.update { it.copy(progressPreview = null) }
            val message = if (written > 0) {
                R.string.settings_reading_progress_import_success
            } else {
                R.string.settings_reading_progress_import_empty
            }
            output.showMessage(message)
        }
    }

    private fun backupOperation(failureMessage: Int, operation: suspend () -> Unit) {
        if (stateInternal.value.isBusy) return
        stateInternal.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            try {
                operation()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Logger.logError(
                    tag = "GeneralPreferencesViewModel",
                    message = "Settings backup failed: ${exception.javaClass.simpleName}",
                )
                output.showMessage(failureMessage)
            } finally {
                stateInternal.update { it.copy(isBusy = false) }
            }
        }
    }
}

data class GeneralPreferencesUiState(
    val showDialog: GeneralPreferencesDialog? = null,
    val isBusy: Boolean = false,
    val legacyPreview: LegacyShelfImportPreview? = null,
    val progressPreview: ReadingProgressPreview? = null,
    /** 是否显示彩蛋导入入口（关于页连点应用名 5 下后开启）。 */
    val showLegacyShelfImport: Boolean = false,
)

sealed interface GeneralPreferencesDialog {
    data object ClearThumbnailCacheDialog : GeneralPreferencesDialog
    data object ResetSettingsDialog : GeneralPreferencesDialog
    data object ExportSettingsDialog : GeneralPreferencesDialog
    data object ImportSettingsDialog : GeneralPreferencesDialog
}

sealed interface GeneralPreferencesUiEvent {
    data object NavigateUp : GeneralPreferencesUiEvent

    data class ShowDialog(val value: GeneralPreferencesDialog?) : GeneralPreferencesUiEvent
    data object ClearThumbnailCache : GeneralPreferencesUiEvent
    data object ResetSettings : GeneralPreferencesUiEvent
    data class ExportSettings(val uri: Uri, val includeNetworkConnections: Boolean) :
        GeneralPreferencesUiEvent
    data class PrepareImport(val uri: Uri) : GeneralPreferencesUiEvent
    data object ImportSettings : GeneralPreferencesUiEvent
    data class PickLegacyFiles(val uris: List<Uri>) : GeneralPreferencesUiEvent
    data object ConfirmLegacyImport : GeneralPreferencesUiEvent
    data object DismissLegacyPreview : GeneralPreferencesUiEvent
    data class ExportNotFound(val uri: Uri) : GeneralPreferencesUiEvent
    data class PickProgressBackup(val uri: Uri) : GeneralPreferencesUiEvent
    data object ConfirmProgressImport : GeneralPreferencesUiEvent
    data object DismissProgressPreview : GeneralPreferencesUiEvent
}
