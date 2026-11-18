package dev.anilbeesetti.nextplayer.feature.videopicker.composables

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.CancelButton
import dev.anilbeesetti.nextplayer.core.ui.components.DoneButton
import dev.anilbeesetti.nextplayer.core.ui.components.NextDialog
import dev.anilbeesetti.nextplayer.core.ui.components.NextOutlinedTextField
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * 通用文本输入弹窗（重命名、密码输入共用同一个输入框外观）。
 *
 * @param stateKey 输入框记忆的 key，切换对象（如换一本书）时重置输入内容。
 */
@Composable
fun TextInputDialog(
    title: String,
    initialValue: String = "",
    isPassword: Boolean = false,
    isError: Boolean = false,
    supportingText: @Composable (() -> Unit)? = null,
    stateKey: Any = title,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var value by rememberSaveable(stateKey) { mutableStateOf(value = initialValue) }
    val focusRequester = remember { FocusRequester() }
    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        content = {
            NextOutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester = focusRequester),
                isError = isError,
                supportingText = supportingText,
                visualTransformation = if (isPassword) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = if (isPassword) {
                    KeyboardOptions(keyboardType = KeyboardType.Password)
                } else {
                    KeyboardOptions.Default
                },
            )
        },
        confirmButton = {
            DoneButton(
                enabled = value.isNotBlank(),
                onClick = { onDone(value) },
            )
        },
        dismissButton = { CancelButton(onClick = onDismiss) },
    )

    // 密码输错时清掉上一次的输入：否则新密码会接在旧密码后面，看起来像「重新输了也不对」
    LaunchedEffect(isError) {
        if (isError && isPassword) value = ""
    }

    LaunchedEffect(key1 = Unit) {
        // To fix focus requester not initialized error on screen rotation
        delay(200.milliseconds)
        focusRequester.requestFocus()
    }
}

@Composable
fun RenameDialog(name: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    TextInputDialog(
        title = stringResource(R.string.rename_to),
        initialValue = name,
        stateKey = name,
        onDismiss = onDismiss,
        onDone = onDone,
    )
}
