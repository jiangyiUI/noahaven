package dev.anilbeesetti.nextplayer.feature.books.shelf

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anilbeesetti.nextplayer.core.common.extensions.isTelevision
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.BookFile
import dev.anilbeesetti.nextplayer.core.model.BookFormat
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.ImageLayoutMode
import dev.anilbeesetti.nextplayer.core.model.MediaLayoutMode
import dev.anilbeesetti.nextplayer.core.model.MediaViewMode
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.ListSectionTitle
import dev.anilbeesetti.nextplayer.core.ui.components.NextSegmentedListItem
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.FolderItem
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.InfoChip
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.lcm
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 书架视图，结构与 ComicLibraryView 完全一致：
 * 文件夹树 / 文件夹列表 / 全部书籍三种模式，排序与布局都读书籍自己的偏好。
 * TXT 没有封面，EPUB 有内置封面，两者都缺省时显示统一的书籍图标。
 */
@Composable
fun BookLibraryView(
    folders: List<Folder>,
    books: List<BookFile>,
    preferences: ApplicationPreferences,
    lastViewedPath: String? = null,
    contentPadding: PaddingValues = PaddingValues(),
    lazyGridState: LazyGridState = rememberLazyGridState(),
    modifier: Modifier = Modifier,
    selectedIds: Set<String> = emptySet(),
    onFolderClick: (String) -> Unit,
    onBookClick: (BookFile) -> Unit,
    onFolderLongClick: (Folder) -> Unit = {},
    onBookLongClick: (BookFile) -> Unit = {},
    showBookPath: Boolean = false,
    subtitleOverride: (BookFile) -> String? = { null },
    /** 非 null 时强制是否显示「文件夹 (N)」标题；null 时跟随 preferences.mediaViewMode。 */
    showFolderSectionHeader: Boolean? = null,
    /** 非 null 时强制是否显示「书籍 (N)」标题；null 时跟随 preferences.mediaViewMode。 */
    showBookSectionHeader: Boolean? = null,
    /** 非 null 时：该书籍加载到列表后，把网格滚动定位到它（来自搜索页点击书籍）。 */
    scrollToBookPath: String? = null,
    /** 定位完成后回调，用于清除外部的待定位标记。 */
    onLocated: (() -> Unit)? = null,
) {
    val isTv = LocalContext.current.isTelevision
    val layoutDirection = LocalLayoutDirection.current
    val folderMinWidth = if (isTv) 160.dp else 90.dp
    val bookMinWidth = if (isTv) 240.dp else 130.dp
    val derivedHeaders = preferences.mediaViewMode == MediaViewMode.FOLDER_TREE
    val showFolderHeader = showFolderSectionHeader ?: derivedHeaders
    val showBookHeader = showBookSectionHeader ?: derivedHeaders
    val booksLabel = stringResource(R.string.books)

    // 外部请求滚动定位到某本书：待该书籍出现在列表中后，计算其网格位置并滚动到位。
    LaunchedEffect(scrollToBookPath, folders, books, showFolderHeader, showBookHeader) {
        val target = scrollToBookPath ?: return@LaunchedEffect
        val gridIndex = bookGridItemIndex(
            folders = folders,
            books = books,
            showFolderHeader = showFolderHeader,
            showBookHeader = showBookHeader,
            targetBookPath = target,
        )
        if (gridIndex < 0) return@LaunchedEffect
        // 等一帧，让 rememberPersistentLazyGridState 的「恢复上次位置」先执行，再被本次定位覆盖
        withFrameNanos { }
        lazyGridState.animateScrollToItem(gridIndex)
        onLocated?.invoke()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = 8.dp
        val itemSpacing = 2.dp
        val maxWidth = maxWidth - (horizontalPadding * 2) - itemSpacing
        val maxFolders = (maxWidth / folderMinWidth).toInt().coerceAtLeast(1)
        val maxBooks = (maxWidth / bookMinWidth).toInt().coerceAtLeast(1)
        val folderColumns = when (preferences.mediaLayoutMode) {
            MediaLayoutMode.LIST -> 1
            MediaLayoutMode.GRID -> maxFolders
        }
        val bookColumns = when (preferences.imageLayoutMode) {
            ImageLayoutMode.LIST -> 1
            ImageLayoutMode.GRID -> maxBooks
            ImageLayoutMode.NINE_GRID -> 3
        }
        val folderSpanCount = if (folders.isEmpty()) 1 else folderColumns
        val bookSpanCount = if (books.isEmpty()) 1 else bookColumns
        val spans = lcm(folderSpanCount, bookSpanCount)
        val singleFolderSpan = spans / folderSpanCount
        val singleBookSpan = spans / bookSpanCount

        LazyVerticalGrid(
            modifier = Modifier.fillMaxSize(),
            state = lazyGridState,
            columns = GridCells.Fixed(spans),
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection) + horizontalPadding,
                end = contentPadding.calculateEndPadding(layoutDirection) + horizontalPadding,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            if (showFolderHeader && folders.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListSectionTitle(text = stringResource(R.string.folders) + " (${folders.size})")
                }
            }
            itemsIndexed(
                items = folders,
                key = { _, folder -> folder.path },
                span = { _, _ -> GridItemSpan(singleFolderSpan) },
            ) { index, folder ->
                FolderItem(
                    folder = folder,
                    isRecentlyPlayedFolder = lastViewedPath != null &&
                        lastViewedPath.startsWith(folder.path.trimEnd('/') + "/"),
                    preferences = preferences,
                    isFirstItem = index == 0,
                    isLastItem = index == folders.lastIndex,
                    selected = folder.path in selectedIds,
                    onClick = { onFolderClick(folder.path) },
                    onLongClick = { onFolderLongClick(folder) },
                    mediaCountLabel = booksLabel,
                )
            }
            if (folders.isNotEmpty() && books.isNotEmpty() && (showFolderHeader || showBookHeader)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(modifier = Modifier.size(8.dp))
                }
            }
            if (showBookHeader && books.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListSectionTitle(text = booksLabel + " (${books.size})")
                }
            }
            itemsIndexed(
                items = books,
                key = { _, book -> book.path },
                span = { _, _ -> GridItemSpan(singleBookSpan) },
            ) { index, book ->
                BookItem(
                    book = book,
                    preferences = preferences,
                    layoutMode = preferences.imageLayoutMode,
                    isLastViewed = book.path == lastViewedPath,
                    isFirstItem = index == 0,
                    isLastItem = index == books.lastIndex,
                    selected = Uri.fromFile(File(book.path)).toString() in selectedIds,
                    showPath = showBookPath,
                    subtitleOverride = subtitleOverride(book),
                    onClick = { onBookClick(book) },
                    onLongClick = { onBookLongClick(book) },
                )
            }
        }
    }
}

/**
 * 计算 [targetBookPath] 在 [BookLibraryView] 网格中的绝对 item 序号。
 * 必须与 [BookLibraryView] 内部 LazyVerticalGrid 的 item 排列顺序严格一致，
 * 否则 [LazyGridState.animateScrollToItem] 会滚到错误位置。找不到返回 -1。
 */
private fun bookGridItemIndex(
    folders: List<Folder>,
    books: List<BookFile>,
    showFolderHeader: Boolean,
    showBookHeader: Boolean,
    targetBookPath: String,
): Int {
    val bookIndex = books.indexOfFirst { it.path == targetBookPath }
    if (bookIndex < 0) return -1
    var index = 0
    // 1. 文件夹段标题
    if (showFolderHeader && folders.isNotEmpty()) index++
    // 2. 文件夹项
    index += folders.size
    // 3. 文件夹与书籍段之间的间隔
    if (folders.isNotEmpty() && books.isNotEmpty() && (showFolderHeader || showBookHeader)) index++
    // 4. 书籍段标题
    if (showBookHeader && books.isNotEmpty()) index++
    // 5. 目标书籍在书籍段内的偏移
    return index + bookIndex
}

/** 搜索结果用：单列自适应网格，不显示文件夹。 */
@Composable
fun BookGrid(
    books: List<BookFile>,
    preferences: ApplicationPreferences = ApplicationPreferences(),
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
    onBookClick: (BookFile) -> Unit,
) {
    LazyVerticalGrid(
        modifier = modifier.fillMaxSize(),
        columns = GridCells.Adaptive(minSize = 130.dp),
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items = books, key = { it.path }) { book ->
            BookItem(
                book = book,
                preferences = preferences,
                layoutMode = ImageLayoutMode.GRID,
                onClick = { onBookClick(book) },
            )
        }
    }
}

/** 收藏书架用：列表/网格跟随书籍偏好，长按可移除。 */
@Composable
fun BookShelfGrid(
    books: List<BookFile>,
    preferences: ApplicationPreferences,
    lastViewedPath: String? = null,
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    selectedIds: Set<String> = emptySet(),
    onBookClick: (BookFile) -> Unit,
    onBookLongClick: (BookFile) -> Unit = {},
) {
    val layoutDirection = LocalLayoutDirection.current
    val layoutMode = preferences.imageLayoutMode
    val columns = when (layoutMode) {
        ImageLayoutMode.LIST -> GridCells.Fixed(1)
        ImageLayoutMode.GRID -> GridCells.Adaptive(minSize = 130.dp)
        ImageLayoutMode.NINE_GRID -> GridCells.Fixed(3)
    }
    LazyVerticalGrid(
        modifier = modifier.fillMaxSize(),
        columns = columns,
        state = state,
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection) + 8.dp,
            end = contentPadding.calculateEndPadding(layoutDirection) + 8.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(
            items = books,
            key = { _, book -> book.path },
        ) { index, book ->
            BookItem(
                book = book,
                preferences = preferences,
                layoutMode = layoutMode,
                isLastViewed = book.path == lastViewedPath,
                isFirstItem = index == 0,
                isLastItem = index == books.lastIndex,
                selected = Uri.fromFile(File(book.path)).toString() in selectedIds,
                onClick = { onBookClick(book) },
                onLongClick = { onBookLongClick(book) },
            )
        }
    }
}

@Composable
fun BookItem(
    book: BookFile,
    preferences: ApplicationPreferences,
    modifier: Modifier = Modifier,
    layoutMode: ImageLayoutMode = ImageLayoutMode.GRID,
    isLastViewed: Boolean = false,
    isFirstItem: Boolean = false,
    isLastItem: Boolean = false,
    selected: Boolean = false,
    showPath: Boolean = false,
    subtitleOverride: String? = null,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    when (layoutMode) {
        ImageLayoutMode.LIST -> BookListItem(
            book = book,
            preferences = preferences,
            modifier = modifier,
            isLastViewed = isLastViewed,
            isFirstItem = isFirstItem,
            isLastItem = isLastItem,
            selected = selected,
            showPath = showPath,
            subtitleOverride = subtitleOverride,
            onClick = onClick,
            onLongClick = onLongClick,
        )

        ImageLayoutMode.GRID -> BookGridItem(
            book = book,
            preferences = preferences,
            modifier = modifier,
            isLastViewed = isLastViewed,
            isFirstItem = isFirstItem,
            isLastItem = isLastItem,
            selected = selected,
            showPath = showPath,
            subtitleOverride = subtitleOverride,
            onClick = onClick,
            onLongClick = onLongClick,
        )

        // 九宫格只显示封面
        ImageLayoutMode.NINE_GRID -> BookGridItem(
            book = book,
            preferences = preferences,
            modifier = modifier,
            isLastViewed = isLastViewed,
            isFirstItem = isFirstItem,
            isLastItem = isLastItem,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
            showFields = false,
            layoutMode = ImageLayoutMode.NINE_GRID,
        )
    }
}

/**
 * 书架网格缺省封面的渐变色池，从 shuangdu 原样抄来。
 * 九宫格无封面时按书名 hash 选一组，形成随机但稳定的视觉。
 */
private val CoverGradients = listOf(
    Color(0xFF3E5B52) to Color(0xFF7FA892),
    Color(0xFF5B4A63) to Color(0xFFA896B5),
    Color(0xFF6B4A3E) to Color(0xFFB58F72),
    Color(0xFF3C4A63) to Color(0xFF8CA0BE),
    Color(0xFF635236) to Color(0xFFAF9A6C),
    Color(0xFF44584A) to Color(0xFF87A98C),
    Color(0xFF5A3F45) to Color(0xFFA87F86),
    Color(0xFF354B52) to Color(0xFF7EA0A8),
)

/** 列表布局缺省缩略图用的纯色，8 种，同样按书名 hash 选。 */
private val ListCoverColors = listOf(
    Color(0xFF4B6E62),
    Color(0xFF6E5D78),
    Color(0xFF7B5D50),
    Color(0xFF506078),
    Color(0xFF7A6848),
    Color(0xFF58725E),
    Color(0xFF6E5258),
    Color(0xFF49626B),
)

private fun gradientPairFor(title: String): Pair<Color, Color> {
    val h = title.hashCode().let { if (it < 0) -it else it }
    return CoverGradients[h % CoverGradients.size]
}

private fun listColorFor(title: String): Color {
    val h = title.hashCode().let { if (it < 0) -it else it }
    return ListCoverColors[h % ListCoverColors.size]
}

/**
 * 封面：EPUB 用扫描时抽出的缓存图；TXT 或没有封面时按布局显示缺省样式。
 * 九宫格（NINE_GRID）：shuangdu 风格的垂直渐变 + 书名；
 * 列表（LIST）：纯色背景 + 书籍 icon。
 */
@Composable
private fun BookThumbnail(
    book: BookFile,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    showProgress: Boolean = false,
    layoutMode: ImageLayoutMode = ImageLayoutMode.LIST,
) {
    val cover = rememberBookCover(book)
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        if (cover == null) {
            // 无封面的缺省绘制
            when (layoutMode) {
                ImageLayoutMode.LIST -> {
                    // 列表布局用纯色背景 + 书籍 icon
                    val bg = listColorFor(book.title)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(bg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = NextIcons.Books,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.92f),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                else -> {
                    // GRID / NINE_GRID 都用 shuangdu 风格的垂直渐变 + 书名
                    val (top, bottom) = gradientPairFor(book.title)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(top, bottom))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = book.title.take(8),
                            color = Color.White.copy(alpha = 0.92f),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 17.sp,
                            ),
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }
        cover?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = book.title,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (showProgress && book.hasProgress) {
            val fraction = if (book.finished) 1f else book.percent.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = fraction)
                        .fillMaxHeight()
                        .clip(shape = CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BookListItem(
    book: BookFile,
    preferences: ApplicationPreferences,
    modifier: Modifier,
    isLastViewed: Boolean,
    isFirstItem: Boolean,
    isLastItem: Boolean,
    selected: Boolean,
    showPath: Boolean = false,
    subtitleOverride: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val highlight = isLastViewed && preferences.markLastPlayedMedia
    val details = bookDetails(book, preferences)
    NextSegmentedListItem(
        modifier = modifier,
        selected = selected,
        contentPadding = PaddingValues(8.dp),
        colors = ListItemDefaults.segmentedColors(
            contentColor = if (highlight) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            supportingContentColor = if (highlight) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            selectedContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
        ),
        isFirstItem = isFirstItem,
        isLastItem = isLastItem,
        onClick = onClick,
        onLongClick = onLongClick,
        leadingContent = {
            if (preferences.showThumbnailField) {
                BookThumbnail(
                    book = book,
                    modifier = Modifier.size(52.dp),
                    showProgress = preferences.showPlayedProgress,
                    layoutMode = ImageLayoutMode.LIST,
                )
            }
            // 不选缩略图时 leadingContent 留空，直接只有标题 + 其他已选字段
        },
        content = {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium.copy(lineBreak = LineBreak.Simple),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    book.finished -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    highlight -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        },
        supportingContent = when {
            // 搜索结果用：用外部传入的副文本（如分组·格式·大小·进度）整体接管第二行，避免与 details 重复。
            subtitleOverride != null -> {
                {
                    Text(
                        text = subtitleOverride,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            details.isNotEmpty() || showPath -> {
                {
                    Column {
                        if (details.isNotEmpty()) {
                            Text(
                                text = details.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (showPath) {
                            Text(
                                text = book.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(
                                    top = if (details.isNotEmpty()) 2.dp else 4.dp,
                                ),
                            )
                        }
                    }
                }
            }

            else -> null
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BookGridItem(
    book: BookFile,
    preferences: ApplicationPreferences,
    modifier: Modifier,
    isLastViewed: Boolean,
    isFirstItem: Boolean,
    isLastItem: Boolean,
    selected: Boolean,
    showPath: Boolean = false,
    subtitleOverride: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    showFields: Boolean = true,
    layoutMode: ImageLayoutMode = ImageLayoutMode.GRID,
) {
    NextSegmentedListItem(
        modifier = modifier.fillMaxWidth(),
        selected = selected,
        contentPadding = PaddingValues(8.dp),
        colors = ListItemDefaults.segmentedColors(
            selectedContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
        ),
        isFirstItem = isFirstItem,
        isLastItem = isLastItem,
        onClick = onClick,
        onLongClick = onLongClick,
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 书籍封面用 2:3，TXT 的默认图标也是同样的版式
                        .aspectRatio(2f / 3f),
                ) {
                    // 网格与九宫格统一走封面机制：有封面显示封面，无封面显示渐变+书名缺省，
                    // 不再受 showThumbnailField 开关影响，与九宫格保持一致。
                    BookThumbnail(
                        book = book,
                        modifier = Modifier.fillMaxSize(),
                        showProgress = preferences.showPlayedProgress,
                        layoutMode = layoutMode,
                    )
                    if (showFields && preferences.showExtensionField) {
                        InfoChip(
                            text = book.format.name,
                            modifier = Modifier
                                .padding(5.dp)
                                .align(Alignment.BottomStart),
                            backgroundColor = Color.Black.copy(alpha = 0.6f),
                            contentColor = Color.White,
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                    }
                    if (showFields && book.percent > 0f) {
                        InfoChip(
                            text = "${(book.percent * 100f).toInt()}%",
                            modifier = Modifier
                                .padding(5.dp)
                                .align(Alignment.BottomEnd),
                            backgroundColor = Color.Black.copy(alpha = 0.6f),
                            contentColor = Color.White,
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                    }
                    if (selected) {
                        Icon(
                            imageVector = NextIcons.Check,
                            contentDescription = null,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(5.dp)
                                .size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else if (book.finished) {
                        Surface(
                            modifier = Modifier
                                .padding(6.dp)
                                .align(Alignment.TopEnd)
                                .size(22.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shadowElevation = 2.dp,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = NextIcons.Check,
                                    contentDescription = stringResource(
                                        R.string.watched_badge_content_desc,
                                    ),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
                Text(
                    text = book.title,
                    maxLines = 2,
                    style = MaterialTheme.typography.titleMedium.copy(lineBreak = LineBreak.Simple),
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = when {
                        book.finished -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        isLastViewed && preferences.markLastPlayedMedia ->
                            MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                // 搜索结果用：subtitleOverride 优先接管（分组·格式·大小·进度），否则按 showPath 显示路径。
                val subtitleText = subtitleOverride ?: if (showPath) book.path else null
                if (subtitleText != null) {
                    Text(
                        text = subtitleText,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        },
    )
}

/** 列表模式下显示在标题下方的信息，字段开关跟随快捷设置。 */
@Composable
private fun bookDetails(book: BookFile, preferences: ApplicationPreferences): List<String> = buildList {
    if (book.author.isNotBlank()) add(book.author)
    if (preferences.showExtensionField) add(book.format.name)
    if (preferences.showSizeField) add(book.formattedFileSize)
    if (book.percent > 0f) add("${(book.percent * 100f).toInt()}%")
}

/** 只读扫描时缓存的封面；TXT 没有封面直接返回 null，交给默认图标。 */
@Composable
fun rememberBookCover(book: BookFile): ImageBitmap? {
    var cover by remember(book.path, book.coverCachePath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(book.path, book.coverCachePath) {
        if (book.format != BookFormat.EPUB) {
            cover = null
            return@LaunchedEffect
        }
        cover = withContext(Dispatchers.IO) {
            book.coverCachePath
                ?.let(::File)
                ?.takeIf { it.exists() }
                ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                ?.asImageBitmap()
        }
    }
    return cover
}

@Composable
fun NoBooksFound(contentPadding: PaddingValues = PaddingValues()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = NextIcons.Books,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.no_books_found),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
