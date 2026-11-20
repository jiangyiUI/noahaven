package dev.anilbeesetti.nextplayer.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.ClickablePreferenceItem
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.PreferenceSwitch
import dev.anilbeesetti.nextplayer.core.ui.components.rememberRestorableFocusState
import dev.anilbeesetti.nextplayer.core.ui.components.restorableFocusGroup
import dev.anilbeesetti.nextplayer.core.ui.components.restorableFocusItem
import dev.anilbeesetti.nextplayer.core.ui.components.tvFocusDown
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.settings.update.UpdateViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

data class SettingsOutput(val navigateUp: () -> Unit, val openSetting: (Setting) -> Unit)

private sealed interface SettingsAction {
    data object NavigateUp : SettingsAction
    data class OpenSetting(val setting: Setting) : SettingsAction
    data object CheckForUpdates : SettingsAction
    data object ToggleAutoCheckUpdate : SettingsAction
}

@Composable
fun SettingsScreen(
    output: SettingsOutput,
    updateViewModel: UpdateViewModel = koinViewModel(),
    preferencesRepository: PreferencesRepository = koinInject(),
) {
    val prefs by preferencesRepository.applicationPreferences.collectAsStateWithLifecycle(
        initialValue = null,
    )

    val scope = rememberCoroutineScope()
    val prefsNonNull = prefs ?: ApplicationPreferences()

    SettingsScreenContent(
        prefs = prefsNonNull,
        onAction = { action ->
            when (action) {
                is SettingsAction.NavigateUp -> output.navigateUp()
                is SettingsAction.OpenSetting -> output.openSetting(action.setting)
                is SettingsAction.CheckForUpdates -> updateViewModel.checkForUpdates()
                SettingsAction.ToggleAutoCheckUpdate -> {
                    scope.launch {
                        preferencesRepository.updateApplicationPreferences {
                            it.copy(autoCheckUpdate = !prefsNonNull.autoCheckUpdate)
                        }
                    }
                }
            }
        },
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
private fun SettingsScreenContent(
    prefs: ApplicationPreferences?,
    onAction: (SettingsAction) -> Unit,
) {
    val settingRows = remember { SettingRow.entries }
    val focusState = rememberRestorableFocusState()

    // 旧块：APPEARANCE → ABOUT（前 8 行）
    val oldSectionRows = settingRows.filter { it != SettingRow.UPDATE }
    val autoCheckEnabled = prefs?.autoCheckUpdate ?: true

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.settings),
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = { onAction(SettingsAction.NavigateUp) },
                        modifier = Modifier.tvFocusDown(focusState.requester),
                    ) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = stringResource(id = R.string.navigate_up),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = rememberScrollState())
                .restorableFocusGroup(state = focusState)
                .padding(paddingValues = innerPadding)
                .padding(horizontal = 16.dp)
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            oldSectionRows.forEachIndexed { index, row ->
                key(row) {
                    ClickablePreferenceItem(
                        modifier = Modifier.restorableFocusItem(
                            state = focusState,
                            row.name,
                        ),
                        title = stringResource(id = row.titleResId),
                        description = stringResource(id = row.descriptionResId),
                        icon = row.icon,
                        onClick = { onAction(SettingsAction.OpenSetting(row.setting!!)) },
                        isFirstItem = index == 0,
                        isLastItem = index == oldSectionRows.lastIndex,
                    )
                }
            }

            // 与上面旧块拉开 24dp，独立成新的一组（更新）
            Spacer(modifier = Modifier.height(16.dp))

            key(SettingRow.UPDATE) {
                ClickablePreferenceItem(
                    modifier = Modifier.restorableFocusItem(
                        state = focusState,
                        SettingRow.UPDATE.name,
                    ),
                    title = stringResource(id = SettingRow.UPDATE.titleResId),
                    description = stringResource(id = SettingRow.UPDATE.descriptionResId),
                    icon = SettingRow.UPDATE.icon,
                    onClick = { onAction(SettingsAction.CheckForUpdates) },
                    isFirstItem = true,
                    isLastItem = false,
                )
            }

            PreferenceSwitch(
                modifier = Modifier.restorableFocusItem(
                    state = focusState,
                    "UPDATE_AUTO_CHECK_SWITCH",
                ),
                title = stringResource(R.string.auto_check_update_name),
                description = stringResource(R.string.auto_check_update_desc),
                icon = NextIcons.AutoCheck,
                isChecked = autoCheckEnabled,
                onClick = { onAction(SettingsAction.ToggleAutoCheckUpdate) },
                isFirstItem = false,
                isLastItem = true,
            )
        }
    }
}

enum class Setting {
    APPEARANCE,
    MEDIA_LIBRARY,
    PLAYER,
    GESTURES,
    AUDIO,
    SUBTITLE,
    GENERAL,
    ABOUT,
    UPDATE,
}

private enum class SettingRow(
    val titleResId: Int,
    val descriptionResId: Int,
    val icon: ImageVector,
    /** null 表示这一行不跳转页面，而是直接执行检查更新。 */
    val setting: Setting?,
) {
    APPEARANCE(
        titleResId = R.string.appearance_name,
        descriptionResId = R.string.appearance_description,
        icon = NextIcons.Appearance,
        setting = Setting.APPEARANCE,
    ),
    MEDIA_LIBRARY(
        titleResId = R.string.media_library,
        descriptionResId = R.string.media_library_description,
        icon = NextIcons.Movie,
        setting = Setting.MEDIA_LIBRARY,
    ),
    PLAYER(
        titleResId = R.string.player_name,
        descriptionResId = R.string.player_description,
        icon = NextIcons.Player,
        setting = Setting.PLAYER,
    ),
    GESTURES(
        titleResId = R.string.gestures_name,
        descriptionResId = R.string.gestures_description,
        icon = NextIcons.SwipeHorizontal,
        setting = Setting.GESTURES,
    ),
    AUDIO(
        titleResId = R.string.audio,
        descriptionResId = R.string.audio_desc,
        icon = NextIcons.Audio,
        setting = Setting.AUDIO,
    ),
    SUBTITLE(
        titleResId = R.string.subtitle,
        descriptionResId = R.string.subtitle_desc,
        icon = NextIcons.Subtitle,
        setting = Setting.SUBTITLE,
    ),
    GENERAL(
        titleResId = R.string.general_name,
        descriptionResId = R.string.general_description,
        icon = NextIcons.ExtraSettings,
        setting = Setting.GENERAL,
    ),
    ABOUT(
        titleResId = R.string.about_name,
        descriptionResId = R.string.about_description,
        icon = NextIcons.Info,
        setting = Setting.ABOUT,
    ),
    UPDATE(
        titleResId = R.string.check_update_name,
        descriptionResId = R.string.check_update_description,
        icon = NextIcons.Update,
        setting = null,
    ),
}
