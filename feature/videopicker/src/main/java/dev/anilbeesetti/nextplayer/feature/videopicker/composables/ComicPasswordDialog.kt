package dev.anilbeesetti.nextplayer.feature.videopicker.composables

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.anilbeesetti.nextplayer.core.ui.R

/**
 * 加密漫画打开时的密码输入弹窗。输入错误时停留在弹窗内并提示。
 */
@Composable
fun ComicPasswordDialog(
    fileName: String,
    wrongPassword: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    TextInputDialog(
        title = stringResource(R.string.comic_password_title, fileName),
        initialValue = "",
        isPassword = true,
        isError = wrongPassword,
        supportingText = if (wrongPassword) {
            { Text(text = stringResource(R.string.comic_password_wrong)) }
        } else {
            null
        },
        stateKey = fileName,
        onDismiss = onDismiss,
        onDone = onSubmit,
    )
}

/** 加密 PDF 但系统版本不支持（低于 Android 15）时的提示。 */
@Composable
fun ComicPasswordUnsupportedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.comic_password_unsupported_title)) },
        text = { Text(text = stringResource(R.string.comic_password_unsupported_message)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.comic_password_ok))
            }
        },
    )
}
