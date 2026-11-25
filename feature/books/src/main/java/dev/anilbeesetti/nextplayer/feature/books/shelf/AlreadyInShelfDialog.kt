package dev.anilbeesetti.nextplayer.feature.books.shelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.DoneButton
import dev.anilbeesetti.nextplayer.core.ui.components.NextDialog
import java.io.File

/**
 * 多选加入书架时，部分书已在书架：合并成一个对话框列出每本书及其所在分组，
 * 避免每本弹一个 toast。只提示，不做操作，点「完成」关闭。
 */
@Composable
fun AlreadyInShelfDialog(
    pathToGroup: Map<String, Long>,
    groupLabelFor: (Long) -> String,
    onDismiss: () -> Unit,
) {
    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.book_shelf_already_in_shelf_title)) },
        content = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(state = rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pathToGroup.forEach { (path, groupId) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = File(path).nameWithoutExtension,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                        )
                        Text(text = groupLabelFor(groupId))
                    }
                }
            }
        },
        confirmButton = { DoneButton(onClick = onDismiss) },
    )
}
