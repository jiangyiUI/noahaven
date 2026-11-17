package dev.anilbeesetti.nextplayer.navigation

import android.content.Context
import androidx.core.net.toUri
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.feature.more.navigation.moreEntry
import dev.anilbeesetti.nextplayer.feature.more.navigation.navigateToTrash
import dev.anilbeesetti.nextplayer.feature.more.navigation.trashEntry
import dev.anilbeesetti.nextplayer.feature.network.navigation.navigateToNetwork
import dev.anilbeesetti.nextplayer.feature.playlist.navigation.PlaylistListRoute
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.navigateToVault
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.vaultEntry
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToSettings

fun EntryProviderScope<NavKey>.moreNavGraph(context: Context, backStack: NavBackStack<NavKey>) {
    moreEntry(
        onPlayVideo = { video -> context.playHistoryMedia(video) },
        onPlayUri = { context.startPlayback(it.toUri()) },
        onSettingsClick = backStack::navigateToSettings,
        onTrashClick = backStack::navigateToTrash,
        onVaultClick = backStack::navigateToVault,
        onPlaylistsClick = { backStack.add(PlaylistListRoute) },
        onNetworkClick = backStack::navigateToNetwork,
    )

    trashEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
        onPlayVideo = { context.startPlayback(it.toUri()) },
    )

    vaultEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
        // Vault files are served through FileProvider, so read access must be granted at
        // playback time for both PlayerActivity and the (separate) PlayerService component.
        onPlayVideo = { uri ->
            context.startPlayback(
                uri = uri,
                grantReadPermission = true,
            )
        },
        onPlayVideos = { uris ->
            context.startPlayback(
                uris = uris,
                grantReadPermission = true,
            )
        },
    )
}

/** 历史里的音频走音频播放器，视频仍走原视频播放器。 */
private fun Context.playHistoryMedia(video: Video) {
    val uri = video.uriString.toUri()
    if (video.isAudio) {
        startAudioPlayback(uris = listOf(element = uri), startUri = uri)
    } else {
        startPlayback(uri = uri)
    }
}
