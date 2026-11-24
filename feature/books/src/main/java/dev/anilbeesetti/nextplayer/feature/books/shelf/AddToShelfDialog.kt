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
import androidx.compose.runtime.remember
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
 * 加入书架时选择分组：单选已有分组（含「未分组」，按层级缩进展示），或输入名字新建。
 * 没有任何分组时确认会自动创建默认分组（分组名由调用方本地化后传入）。
 *
 * @param title 弹窗标题，移动/复制到分组时由调用方换成对应文案。
 */
@Composable
fun AddToShelfDialog(
    groups: List<BookShelfGroup>,
    ungroupedId: Long?,
    ungroupedLabel: String,
    defaultGroupName: String,
    onDismiss: () -> Unit,
    onConfirm: (groupId: Long?, newGroupName: String) -> Unit,
    title: String? = null,
) {
    var selectedGroupId by rememberSaveable(groups, ungroupedId) {
        mutableStateOf(ungroupedId ?: groups.firstOrNull()?.id)
    }
    var newGroupName by rememberSaveable { mutableStateOf("") }
    val hasGroups = ungroupedId != null || groups.isNotEmpty()
    val canConfirm = !hasGroups || selectedGroupId != null || newGroupName.isNotBlank()
    val ordered = remember(groups, ungroupedId) { groupsInDisplayOrder(groups, ungroupedId) }

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
                ungroupedId?.let { uid ->
                    GroupRadioRow(
                        selected = selectedGroupId == uid && newGroupName.isBlank(),
                        label = ungroupedLabel,
                        depth = 0,
                        onClick = {
                            selectedGroupId = uid
                            newGroupName = ""
                        },
                    )
                }
                ordered.forEach { (group, depth) ->
                    GroupRadioRow(
                        selected = selectedGroupId == group.id && newGroupName.isBlank(),
                        label = group.name,
                        depth = depth,
                        onClick = {
                            selectedGroupId = group.id
                            newGroupName = ""
                        },
                    )
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

/** 分组单选行：按深度缩进，与分组目标选择器保持一致视觉。 */
@Composable
private fun GroupRadioRow(
    selected: Boolean,
    label: String,
    depth: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(
                start = (8 + depth * 16).dp,
                top = 8.dp,
                bottom = 8.dp,
                end = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
