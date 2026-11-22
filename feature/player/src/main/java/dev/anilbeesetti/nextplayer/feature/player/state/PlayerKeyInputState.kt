package dev.anilbeesetti.nextplayer.feature.player.state

import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

@Composable
internal fun rememberPlayerKeyInputState(
    player: Player,
    controls: ControlsVisibilityState,
    seekIncrementMs: Long,
    dpadUpDown: Int? = null,
    seekEvenWhenControlsVisible: Boolean = false,
    onSeekFeedback: (Long) -> Unit = {},
): PlayerKeyInputState {
    val audioManager = LocalContext.current.getSystemService(AudioManager::class.java)
    val state = remember(
        player,
        controls,
        seekIncrementMs,
        dpadUpDown,
        seekEvenWhenControlsVisible,
    ) {
        PlayerKeyInputState(
            player,
            controls,
            seekIncrementMs,
            dpadUpDown,
            seekEvenWhenControlsVisible,
            onSeekFeedback,
        ) { up ->
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI,
            )
        }
    }
    return state
}

@Stable
internal class PlayerKeyInputState(
    private val player: Player,
    private val controls: ControlsVisibilityState,
    private val seekIncrementMs: Long,
    private val dpadUpDown: Int? = null,
    /** 非电视设备没有焦点导航需求，控制条显示时左右键也直接快进快退。 */
    private val seekEvenWhenControlsVisible: Boolean = false,
    /** 快进快退时通知界面显示与双击同一套左右侧提示。 */
    private val onSeekFeedback: (Long) -> Unit = {},
    private val adjustVolume: (Boolean) -> Unit = {},
) {
    @OptIn(UnstableApi::class)
    fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        if (keyEvent.key == Key.Back && !controls.controlsLocked) {
            if (!controls.controlsVisible) return false // controls already hidden: let BACK exit
            if (keyEvent.type == KeyEventType.KeyUp) controls.hideControls()
            return true
        }
        if (keyEvent.type != KeyEventType.KeyDown) return false
        if (controls.controlsLocked) {
            return when (keyEvent.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    if (controls.controlsVisible) {
                        controls.unlockControls()
                    } else {
                        controls.showControls()
                    }
                    true
                }

                else -> {
                    controls.showControls()
                    false
                }
            }
        }

        fun seekBy(deltaMs: Long) {
            val duration = player.duration
            val target = (player.currentPosition + deltaMs).coerceAtLeast(minimumValue = 0)
            player.seekTo(
                if (duration > 0) {
                    target.coerceAtMost(maximumValue = duration)
                } else {
                    target
                },
            )
        }

        fun togglePlayPause() {
            if (player.isPlaying) player.pause() else player.play()
        }

        return when (keyEvent.key) {
            Key.MediaPlayPause, Key.Spacebar -> {
                togglePlayPause()
                controls.showControls()
                true
            }

            Key.MediaPlay -> {
                player.play()
                controls.showControls()
                true
            }

            Key.MediaPause -> {
                player.pause()
                controls.showControls()
                true
            }

            Key.MediaFastForward -> {
                seekBy(deltaMs = seekIncrementMs)
                controls.showControls()
                true
            }

            Key.MediaRewind -> {
                seekBy(deltaMs = -seekIncrementMs)
                controls.showControls()
                true
            }

            Key.MediaNext -> {
                player.seekToNext()
                controls.showControls()
                true
            }

            Key.MediaPrevious -> {
                player.seekToPrevious()
                controls.showControls()
                true
            }

            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                val controlsWereHidden = !controls.controlsVisible
                controls.showControls()
                controlsWereHidden
            }

            Key.DirectionLeft -> {
                if (!controls.controlsVisible || seekEvenWhenControlsVisible) {
                    seekBy(deltaMs = -seekIncrementMs)
                    onSeekFeedback(-seekIncrementMs)
                    true
                } else {
                    controls.showControls()
                    false
                }
            }

            Key.DirectionRight -> {
                if (!controls.controlsVisible || seekEvenWhenControlsVisible) {
                    seekBy(deltaMs = seekIncrementMs)
                    onSeekFeedback(seekIncrementMs)
                    true
                } else {
                    controls.showControls()
                    false
                }
            }

            Key.DirectionUp, Key.DirectionDown -> {
                if (!controls.controlsVisible) {
                    when (dpadUpDown) {
                        1 -> if (keyEvent.key ==
                            Key.DirectionUp
                        ) {
                            player.seekToNextMediaItem()
                        } else {
                            player.seekToPreviousMediaItem()
                        }

                        0 -> adjustVolume(keyEvent.key == Key.DirectionUp)

                        else -> controls.showControls()
                    }
                    true
                } else {
                    controls.showControls()
                    false
                }
            }

            else -> false
        }
    }
}
