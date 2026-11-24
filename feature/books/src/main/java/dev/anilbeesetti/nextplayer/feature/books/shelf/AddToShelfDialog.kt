package dev.anilbeesetti.nextplayer.feature.books.shelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.CancelButton
import dev.anilbeesetti.nextplayer.core.ui.components.DoneButton
import dev.anilbeesetti.nextplayer.core.ui.components.NextDialog
import dev.anilbeesetti.nextplayer.feature.books.data.BookShelfGroup

/**
 * 加入书架时选择分组：单选已有分组，或输入名字新建。
 * 没有任何分组时确认会自动创建默认分组（分组名由调用方本地化后传入）。
 *
 * @param title 弹窗标题，移动/复制到分组时由调用方换成对应文案。
 */
@Composable
fun AddToShelfDialog(
    groups: List<BookShelfGroup>,
    defaultGroupName: String,
    onDismiss: () -> Unit,
    onConfirm: (groupId: Long?, newGroupName: String) -> Unit,
    title: String? = null,
) {
    var selectedGroupId by rememberSaveable(groups) {
        mutableStateOf(groups.firstOrNull()?.id)
    }
    var newGroupName by rememberSaveable { mutableStateOf("") }
    val hasGroups = groups.isNotEmpty()
    val canConfirm = !hasGroups || selectedGroupId != null || newGroupName.isNotBlank()

    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title ?: stringResource(R.string.add_to_shelf_title)) },
        content = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(state = rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                groups.forEach { group ->
                    val selected = selectedGroupId == group.id && newGroupName.isBlank()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = {
                                    selectedGroupId = group.id
                                    newGroupName = ""
                                },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(
                            text = group.name,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                if (!hasGroups) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = true, onClick = null)
                        Text(
                            text = defaultGroupName,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = {
                        selectedGroupId = null
                        newGroupName = it
                    },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.book_shelf_group_name)) },
                    placeholder = { Text(text = stringResource(R.string.book_shelf_new_group)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            DoneButton(
                enabled = canConfirm,
                onClick = {
                    onConfirm(
                        selectedGroupId.takeIf { newGroupName.isBlank() },
                        newGroupName.trim(),
                    )
                },
            )
        },
        dismissButton = {
            CancelButton(onClick = onDismiss)
        },
    )
}
