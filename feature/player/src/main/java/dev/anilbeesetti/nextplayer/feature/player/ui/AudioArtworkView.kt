package dev.anilbeesetti.nextplayer.feature.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.anilbeesetti.nextplayer.core.media.services.AudioArtwork
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons

/**
 * 音频播放画面：显示音频文件内嵌封面，没有封面时显示缺省图。
 */
@Composable
internal fun AudioArtworkView(
    player: Player,
    modifier: Modifier = Modifier,
) {
    var path by remember {
        mutableStateOf(value = player.currentMediaItem?.artworkPath())
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                path = mediaItem.artworkPath()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = path) {
        value = path?.let { AudioArtwork.load(context = context, pathOrUri = it, maxSizePx = 1024) }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val coverSize = (if (maxWidth < maxHeight) maxWidth else maxHeight) * 0.7f
        val cover = bitmap
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(size = coverSize)
                    .clip(shape = RoundedCornerShape(size = 16.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier.size(size = coverSize),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NextIcons.Audio,
                    contentDescription = null,
                    modifier = Modifier.size(size = 96.dp),
                    tint = Color.White.copy(alpha = 0.4f),
                )
            }
        }
    }
}

private fun MediaItem?.artworkPath(): String? =
    this?.localConfiguration?.uri?.toString() ?: this?.mediaId
