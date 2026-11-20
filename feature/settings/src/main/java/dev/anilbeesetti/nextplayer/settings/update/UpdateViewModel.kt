package dev.anilbeesetti.nextplayer.settings.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class UpdateViewModel(
    private val updateManager: UpdateManager,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    val state: StateFlow<UpdateUiState> = updateManager.state

    val mirrors: List<DownloadMirror> = DownloadMirror.BUILT_IN_MIRRORS

    val lastMirrorId: String? get() = updateManager.lastMirrorId

    val lastCustomPrefix: String get() = updateManager.lastCustomPrefix

    fun checkForUpdates() = updateManager.checkForUpdates(force = true)

    fun dismiss() = updateManager.dismiss()

    fun ignoreCurrent() = updateManager.ignoreCurrentRelease()

    /** 关闭启动时自动检查更新（在弹窗点"不再提醒"时调用）。 */
    fun turnOffAutoCheck() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(autoCheckUpdate = false)
            }
        }
        updateManager.dismiss()
    }

    fun chooseMirror() = updateManager.showMirrorSelection()

    fun download(mirror: DownloadMirror, customPrefix: String?) =
        updateManager.download(mirror = mirror, customPrefix = customPrefix)

    fun cancelDownload() = updateManager.cancelDownload()

    fun install() = updateManager.installDownloaded()
}
