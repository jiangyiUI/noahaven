package dev.anilbeesetti.nextplayer.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.utils.PlayerApi
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.PendingImageFolder
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.PendingMusicFolder
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.imageSearchEntry
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.imagesEntry
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.mediaPickerEntry
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.musicEntry
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.musicSearchEntry
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.navigateToImageSearch
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.navigateToMediaPickerScreen
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.navigateToMusicSearch
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.navigateToSearch
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.navigateToVault
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.searchEntry
import dev.anilbeesetti.nextplayer.settings.navigation.navigateToSettings

fun EntryProviderScope<NavKey>.mediaNavGraph(context: Context, backStack: NavBackStack<NavKey>) {
    mediaPickerEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
        onPlayVideo = { uri -> context.startPlayback(uri = uri) },
        onPlayVideos = { uris -> context.startPlayback(uris = uris) },
        onFolderClick = backStack::navigateToMediaPickerScreen,
        onSettingsClick = backStack::navigateToSettings,
        onSearchClick = backStack::navigateToSearch,
        onVaultClick = backStack::navigateToVault,
    )

    searchEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
        onPlayVideo = { uri -> context.startPlayback(uri = uri) },
        onFolderClick = backStack::navigateToMediaPickerScreen,
    )
}

fun EntryProviderScope<NavKey>.musicNavGraph(context: Context, backStack: NavBackStack<NavKey>) {
    musicEntry(
        onPlayAudios = { playlist, startUri ->
            context.startAudioPlayback(uris = playlist, startUri = startUri)
        },
        onSettingsClick = backStack::navigateToSettings,
        onSearchClick = backStack::navigateToMusicSearch,
    )

    musicSearchEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
        onPlayAudios = { playlist, startUri ->
            context.startAudioPlayback(uris = playlist, startUri = startUri)
        },
        onOpenFolder = { folderPath ->
            // 记录目标文件夹，返回音乐库后由页面消费并进入该目录
            PendingMusicFolder.request(folderPath = folderPath)
            backStack.removeLastIfNotRoot()
        },
    )
}

fun EntryProviderScope<NavKey>.imagesNavGraph(backStack: NavBackStack<NavKey>) {
    imagesEntry(
        onSettingsClick = backStack::navigateToSettings,
        onSearchClick = backStack::navigateToImageSearch,
    )

    imageSearchEntry(
        onNavigateUp = { backStack.removeLastIfNotRoot() },
        onOpenFolder = { folderPath ->
            // 记录目标文件夹，返回图片库后由页面消费并进入该目录
            PendingImageFolder.request(folderPath = folderPath)
            backStack.removeLastIfNotRoot()
        },
    )
}

internal fun Context.startPlayback(uri: Uri, grantReadPermission: Boolean = false) {
    startPlayback(
        uri = uri,
        playlist = null,
        grantReadPermission = grantReadPermission,
    )
}

internal fun Context.startPlayback(
    uris: List<Uri>,
    startUri: Uri? = null,
    grantReadPermission: Boolean = false,
) {
    val uri = startUri?.takeIf(predicate = uris::contains) ?: uris.firstOrNull() ?: return
    startPlayback(
        uri = uri,
        playlist = uris,
        grantReadPermission = grantReadPermission,
    )
}

private fun Context.startPlayback(uri: Uri, playlist: List<Uri>?, grantReadPermission: Boolean) {
    if (grantReadPermission) {
        (playlist ?: listOf(element = uri)).forEach {
            grantUriPermission(
                packageName,
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }
    val intent = Intent(
        this,
        PlayerActivity::class.java,
    ).apply {
        action = Intent.ACTION_VIEW
        data = uri
        playlist?.let {
            putExtra(
                "video_list_is_explicit",
                true,
            )
            putParcelableArrayListExtra(
                PlayerApi.API_PLAYLIST,
                ArrayList(it),
            )
        }
        if (grantReadPermission) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(intent)
}

/**
 * 启动音频播放。用音频 MIME 类型标记音频模式，播放页据此显示封面并隐藏视频控件；
 * 不走 [PlayerApi] 的 extras，避免被识别成外部播放器 API 调用。
 */
internal fun Context.startAudioPlayback(uris: List<Uri>, startUri: Uri? = null) {
    val playlist = uris.takeIf { it.isNotEmpty() } ?: return
    val uri = startUri?.takeIf(predicate = playlist::contains) ?: playlist.first()
    val intent = Intent(
        this,
        PlayerActivity::class.java,
    ).apply {
        action = Intent.ACTION_VIEW
        // setType 会清空已设置的 data，必须用 setDataAndType 同时设置
        setDataAndType(uri, "audio/*")
        putExtra("video_list_is_explicit", true)
        putParcelableArrayListExtra(
            PlayerApi.API_PLAYLIST,
            ArrayList(playlist),
        )
    }
    startActivity(intent)
}
