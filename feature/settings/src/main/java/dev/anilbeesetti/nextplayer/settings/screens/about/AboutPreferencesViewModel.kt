package dev.anilbeesetti.nextplayer.settings.screens.about

import android.content.Context
import android.os.Build
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class AboutPreferencesViewModel(
    context: Context,
    private val preferencesRepository: PreferencesRepository,
    @InjectedParam internal var output: Output,
) : MviViewModel<AboutPreferencesUiState, AboutPreferencesAction>() {

    data class Output(
        val navigateUp: () -> Unit,
        val showMessage: (Int) -> Unit = {},
    )

    private val stateInternal =
        MutableStateFlow(value = AboutPreferencesUiState(appVersion = context.appVersion()))
    override val state: StateFlow<AboutPreferencesUiState> = stateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { prefs ->
                stateInternal.update { it.copy(showLegacyShelfImport = prefs.showLegacyShelfImport) }
            }
        }
    }

    /** 连点应用名计数：超过时间窗口则重置，凑够 5 下永久开启导入书架入口。 */
    private var appNameClickCount = 0
    private var lastAppNameClickAt = 0L

    override fun onAction(action: AboutPreferencesAction) {
        when (action) {
            is AboutPreferencesAction.NavigateUp -> output.navigateUp()
            is AboutPreferencesAction.AppNameClicked -> onAppNameClicked()
        }
    }

    private fun onAppNameClicked() {
        if (stateInternal.value.showLegacyShelfImport) return
        val now = System.currentTimeMillis()
        appNameClickCount = if (now - lastAppNameClickAt > APP_NAME_CLICK_WINDOW_MS) {
            1
        } else {
            appNameClickCount + 1
        }
        lastAppNameClickAt = now
        if (appNameClickCount >= APP_NAME_CLICK_THRESHOLD) {
            appNameClickCount = 0
            viewModelScope.launch {
                preferencesRepository.updateApplicationPreferences {
                    it.copy(showLegacyShelfImport = true)
                }
            }
            output.showMessage(R.string.about_legacy_import_enabled)
        }
    }

    private companion object {
        const val APP_NAME_CLICK_THRESHOLD = 5
        const val APP_NAME_CLICK_WINDOW_MS = 2000L
    }
}

data class AboutPreferencesUiState(
    val appVersion: String,
    val showLegacyShelfImport: Boolean = false,
)

sealed interface AboutPreferencesAction {
    data object NavigateUp : AboutPreferencesAction
    data object AppNameClicked : AboutPreferencesAction
}

private fun Context.appVersion(): String {
    val packageInfo = packageManager.getPackageInfo(
        packageName,
        0,
    )

    @Suppress("DEPRECATION")
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        packageInfo.versionCode
    }

    return "${packageInfo.versionName} ($versionCode)"
}
