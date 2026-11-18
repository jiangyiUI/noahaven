package dev.anilbeesetti.nextplayer.settings.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.common.Utils
import dev.anilbeesetti.nextplayer.core.ui.R

/**
 * 设置页承载的全部更新弹窗。状态由 UpdateManager 驱动，
 * 这里只负责展示和把用户操作回传。
 */
@Composable
fun UpdateDialogs(
    state: UpdateUiState,
    mirrors: List<DownloadMirror>,
    lastMirrorId: String?,
    lastCustomPrefix: String,
    onDismiss: () -> Unit,
    onChooseMirror: () -> Unit,
    onDownload: (DownloadMirror, String?) -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    when (state) {
        is UpdateUiState.Idle -> Unit

        is UpdateUiState.Checking -> CheckingDialog(onCancel = onDismiss)

        is UpdateUiState.UpToDate -> MessageDialog(
            title = stringResource(R.string.check_update_name),
            text = stringResource(R.string.update_up_to_date, state.currentVersion),
            confirmText = stringResource(R.string.update_dismiss),
            onDismiss = onDismiss,
        )

        is UpdateUiState.Available -> AvailableDialog(
            state = state,
            onDownload = onChooseMirror,
            onDismiss = onDismiss,
        )

        is UpdateUiState.ChooseMirror -> MirrorDialog(
            state = state,
            mirrors = mirrors,
            lastMirrorId = lastMirrorId,
            lastCustomPrefix = lastCustomPrefix,
            onDownload = onDownload,
            onDismiss = onDismiss,
        )

        is UpdateUiState.Downloading -> DownloadingDialog(
            state = state,
            onCancel = onCancelDownload,
        )

        is UpdateUiState.Downloaded -> MessageDialog(
            title = stringResource(R.string.update_download_done_title),
            text = stringResource(R.string.update_download_done_message),
            confirmText = stringResource(R.string.update_install),
            onConfirm = onInstall,
            onDismiss = onDismiss,
        )

        is UpdateUiState.Message -> MessageDialog(
            title = state.title,
            text = state.text,
            confirmText = stringResource(R.string.update_dismiss),
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun CheckingDialog(onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.check_update_name)) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.update_checking))
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.update_cancel))
            }
        },
    )
}

@Composable
private fun AvailableDialog(
    state: UpdateUiState.Available,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.update_available_title, state.release.tagName))
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = state.release.notes.ifBlank {
                        stringResource(R.string.update_release_notes_empty)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) {
                Text(stringResource(R.string.update_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_later))
            }
        },
    )
}

@Composable
private fun MirrorDialog(
    state: UpdateUiState.ChooseMirror,
    mirrors: List<DownloadMirror>,
    lastMirrorId: String?,
    lastCustomPrefix: String,
    onDownload: (DownloadMirror, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val customMirror = DownloadMirror(id = DownloadMirror.CUSTOM_ID, prefix = "")
    val options = mirrors + customMirror
    var selectedId by rememberSaveable(state) {
        mutableStateOf(lastMirrorId ?: options.first().id)
    }
    var customPrefix by rememberSaveable(state) { mutableStateOf(lastCustomPrefix) }

    val directLabel = stringResource(R.string.update_mirror_direct)
    val customLabel = stringResource(R.string.update_mirror_custom)
    fun labelOf(mirror: DownloadMirror): String = when (mirror.id) {
        "direct" -> directLabel
        DownloadMirror.CUSTOM_ID -> customLabel
        else -> mirror.prefix
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_choose_mirror_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                options.forEach { mirror ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedId == mirror.id,
                                role = Role.RadioButton,
                                onClick = { selectedId = mirror.id },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedId == mirror.id,
                            onClick = null,
                        )
                        Text(
                            text = labelOf(mirror),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                if (selectedId == DownloadMirror.CUSTOM_ID) {
                    OutlinedTextField(
                        value = customPrefix,
                        onValueChange = { customPrefix = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.update_mirror_custom_hint)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedId != DownloadMirror.CUSTOM_ID || customPrefix.isNotBlank(),
                onClick = {
                    val mirror = options.first { it.id == selectedId }
                    onDownload(mirror, customPrefix)
                },
            ) {
                Text(stringResource(R.string.update_mirror_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_cancel))
            }
        },
    )
}

@Composable
private fun DownloadingDialog(
    state: UpdateUiState.Downloading,
    onCancel: () -> Unit,
) {
    val progress = if (state.totalBytes > 0) {
        (state.downloadedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    AlertDialog(
        // 下载中不允许点外部关闭，避免状态和界面不一致
        onDismissRequest = {},
        title = { Text(stringResource(R.string.update_downloading_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = stringResource(
                        R.string.update_downloading_progress,
                        Utils.formatFileSize(state.downloadedBytes),
                        if (state.totalBytes > 0) {
                            Utils.formatFileSize(state.totalBytes)
                        } else {
                            "—"
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.update_cancel))
            }
        },
    )
}

@Composable
private fun MessageDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = {
                    if (onConfirm != null) onConfirm() else onDismiss()
                },
            ) {
                Text(confirmText)
            }
        },
    )
}
