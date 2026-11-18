package dev.anilbeesetti.nextplayer.settings.update

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class UpdateViewModel(private val updateManager: UpdateManager) : ViewModel() {

    val state: StateFlow<UpdateUiState> = updateManager.state

    val mirrors: List<DownloadMirror> = DownloadMirror.BUILT_IN_MIRRORS

    val lastMirrorId: String? get() = updateManager.lastMirrorId

    val lastCustomPrefix: String get() = updateManager.lastCustomPrefix

    fun checkForUpdates() = updateManager.checkForUpdates()

    fun dismiss() = updateManager.dismiss()

    fun chooseMirror() = updateManager.showMirrorSelection()

    fun download(mirror: DownloadMirror, customPrefix: String?) =
        updateManager.download(mirror = mirror, customPrefix = customPrefix)

    fun cancelDownload() = updateManager.cancelDownload()

    fun install() = updateManager.installDownloaded()
}
