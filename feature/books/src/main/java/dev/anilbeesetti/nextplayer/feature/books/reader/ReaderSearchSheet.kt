package dev.anilbeesetti.nextplayer.feature.books.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons

/** 一条全文搜索命中。preview 是命中位置前后的上下文，[highlightStart, highlightEnd) 是关键词在 preview 中的区间。 */
data class SearchHit(
    val chapterIndex: Int,
    val chapterTitle: String,
    val charOffset: Int,
    val preview: String,
    val highlightStart: Int,
    val highlightEnd: Int,
    /** 该命中在所在章节内的页号（1-based），0 表示未知。 */
    val pageNumber: Int,
)

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
)
@Composable
fun ReaderSearchSheet(
    theme: ReaderTheme,
    query: String,
    results: List<SearchHit>,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit = {},
    onSelect: (SearchHit) -> Unit,
    onDismiss: () -> Unit,
    history: List<String> = emptyList(),
    onHistoryClick: (String) -> Unit = {},
    onRemoveHistory: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
) {
    var editHistory by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.isNotEmpty()) editHistory = false
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = theme.surface,
        contentColor = theme.panelText,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = theme.panelSub) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.reader_search),
                    style = androidx.compose.ui.text.TextStyle(
                        color = theme.panelText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.reader_search_hint),
                        color = theme.panelSub,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = NextIcons.Search,
                        contentDescription = null,
                        tint = theme.panelSub,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(
                                imageVector = NextIcons.Close,
                                contentDescription = stringResource(R.string.reader_search_clear),
                                tint = theme.panelSub,
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(50),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = theme.surface,
                    unfocusedContainerColor = theme.surface,
                    focusedTextColor = theme.panelText,
                    unfocusedTextColor = theme.panelText,
                    focusedBorderColor = theme.accent,
                    unfocusedBorderColor = theme.panelSub.copy(alpha = 0.4f),
                    cursorColor = theme.accent,
                ),
            )

            val statusText = when {
                searching -> null
                query.isEmpty() -> null
                results.isEmpty() -> stringResource(R.string.reader_search_no_results)
                else -> stringResource(R.string.reader_search_found, results.size)
            }
            if (statusText != null || searching) {
                Row(
                    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (searching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = theme.accent,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = statusText ?: "",
                        style = androidx.compose.ui.text.TextStyle(
                            color = theme.panelSub,
                            fontSize = 13.sp,
                        ),
                    )
                }
            }

            // 仅在搜索框为空且有历史时展示「本书搜索历史」；非编辑时点 chip 填入搜索词，编辑模式可单删或全部清空。
            if (query.isEmpty() && history.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.reader_search_history),
                        style = androidx.compose.ui.text.TextStyle(
                            color = theme.panelText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (editHistory) {
                        Text(
                            text = stringResource(R.string.clear_all),
                            color = theme.accent,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onClearHistory() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.done),
                            color = theme.accent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { editHistory = false }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    } else {
                        IconButton(onClick = { editHistory = true }) {
                            Icon(
                                imageVector = NextIcons.DeleteSweep,
                                contentDescription = stringResource(R.string.clear_all),
                                tint = theme.panelSub,
                            )
                        }
                    }
                }
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    history.forEach { item ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(theme.panelSub.copy(alpha = 0.12f))
                                .clickable {
                                    if (editHistory) {
                                        onRemoveHistory(item)
                                    } else {
                                        onHistoryClick(item)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = item,
                                    color = theme.panelText,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (editHistory) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = NextIcons.Close,
                                        contentDescription = null,
                                        tint = theme.panelSub,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 按章节分组（搜索时按章节顺序遍历，groupBy 保持出现顺序），同章结果归到同一标题下。
            val grouped = results.groupBy { it.chapterIndex }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                grouped.forEach { (chapterIndex, hits) ->
                    item(key = "header-$chapterIndex") {
                        Text(
                            text = hits.first().chapterTitle,
                            style = androidx.compose.ui.text.TextStyle(
                                color = theme.panelText,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp, bottom = 4.dp),
                        )
                    }
                    items(items = hits, key = { "${it.chapterIndex}-${it.charOffset}" }) { hit ->
                        SearchHitItem(
                            hit = hit,
                            theme = theme,
                            onClick = { onSelect(hit) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHitItem(
    hit: SearchHit,
    theme: ReaderTheme,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        if (hit.pageNumber > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "P.${hit.pageNumber}",
                    style = androidx.compose.ui.text.TextStyle(
                        color = theme.panelSub,
                        fontSize = 12.sp,
                    ),
                )
            }
            Spacer(modifier = Modifier.size(3.dp))
        }
        Text(
            text = buildAnnotatedString {
                val safeEnd = hit.highlightEnd.coerceAtMost(hit.preview.length)
                val safeStart = hit.highlightStart.coerceIn(0, safeEnd)
                append(hit.preview.substring(0, safeStart))
                withStyle(
                    SpanStyle(color = theme.accent, fontWeight = FontWeight.Bold),
                ) {
                    append(hit.preview.substring(safeStart, safeEnd))
                }
                append(hit.preview.substring(safeEnd))
            },
            style = androidx.compose.ui.text.TextStyle(
                color = theme.panelText,
                fontSize = 14.sp,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}
