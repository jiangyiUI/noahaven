package dev.anilbeesetti.nextplayer.feature.books.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.os.BatteryManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.core.ui.theme.LocalDarkTheme
import dev.anilbeesetti.nextplayer.feature.books.model.BookContent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun ReaderScreen(viewModel: ReaderViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler {
        viewModel.saveNow()
        viewModel.output.onNavigateUp()
    }

    val content = state.content
    val settings = state.settings
    val theme = if (LocalDarkTheme.current) settings.darkTheme else settings.lightTheme
    when {
        state.loading -> ReaderPlaceholder(
            theme = theme,
            message = stringResource(R.string.reader_loading),
            actionLabel = stringResource(R.string.reader_back),
            onAction = { viewModel.output.onNavigateUp() },
            loading = true,
        )

        state.error != null || content == null -> ReaderPlaceholder(
            theme = theme,
            message = stringResource(
                if (state.error == ReaderError.CONTENT_MISSING) {
                    R.string.reader_error_content_missing
                } else {
                    R.string.reader_error_book_missing
                },
            ),
            actionLabel = stringResource(R.string.reader_back),
            onAction = { viewModel.output.onNavigateUp() },
            loading = false,
        )

        else -> ReaderContent(
            state = state,
            content = content,
            viewModel = viewModel,
        )
    }
}

@Composable
private fun ReaderContent(
    state: ReaderUiState,
    content: BookContent,
    viewModel: ReaderViewModel,
) {
    val settings = state.settings
    val theme = if (LocalDarkTheme.current) settings.darkTheme else settings.lightTheme
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)

    // 自定义字体按路径加载一次；文件损坏或已被删除时回落到系统默认字体。
    val customTypeface = remember(settings.customFontPath) {
        settings.customFontPath?.let { path ->
            runCatching { Typeface.createFromFile(path) }.getOrNull()
        }
    }

    // 参考实现用字体度量高度（descent - ascent）而不是字号来算行高。
    // 中文字体这个值约为字号的 1.3 倍，所以同样把行距拉满时，行与行之间会明显比按字号算更开。
    val fontMetricsRatio = remember(customTypeface) {
        val typeface = customTypeface ?: Typeface.SANS_SERIF
        val metrics = Paint().apply {
            this.typeface = typeface
            textSize = 100f
        }.fontMetrics
        (metrics.descent - metrics.ascent) / 100f
    }

    val bodyStyle = remember(settings, theme, fontMetricsRatio, customTypeface) {
        val family = customTypeface?.let { FontFamily(it) } ?: FontFamily.SansSerif
        TextStyle(
            color = theme.text,
            fontSize = settings.fontSizeSp.sp,
            // 行高 = 字体度量高度 × (行距 + 0.16)，与参考实现的 setLineSpacing 完全一致
            lineHeight = (
                settings.fontSizeSp * fontMetricsRatio * (settings.lineSpacing + 0.16f)
                ).sp,
            fontFamily = family,
            fontWeight = settings.fontWeightValue,
            // 参考排版关闭了字体额外内边距，行高由我们给的值决定，避免每行再被垫高
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            // 多出来的行距全部留在行底，和 StaticLayout 加行距的方向一致
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Top,
                trim = LineHeightStyle.Trim.None,
            ),
        )
    }
    val headingStyle = remember(settings, theme, fontMetricsRatio, customTypeface) {
        TextStyle(
            color = theme.text,
            fontSize = (settings.fontSizeSp * 1.32f).sp,
            // 标题行高同样按度量高度算：1.32 倍字号的行 × 行距，再加 0.16 的底空
            lineHeight = (
                settings.fontSizeSp * fontMetricsRatio *
                    (1.32f * settings.lineSpacing + 0.16f)
                ).sp,
            fontWeight = FontWeight.Bold,
            fontFamily = customTypeface?.let { FontFamily(it) } ?: FontFamily.SansSerif,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Top,
                trim = LineHeightStyle.Trim.None,
            ),
        )
    }
    val paragraphSpacingPx = with(density) {
        // 参考实现是在两段之间插一行「零宽字符」，这一行同样会被行距倍数放大，
        // 所以段间距 = 字体度量高度 ×(段距 × 行距 + 0.16)；段距为 0 时不插这一行。
        val gapFactor = if (settings.paragraphSpacing <= 0.04f) {
            0f
        } else {
            settings.paragraphSpacing * settings.lineSpacing + 0.16f
        }
        (settings.fontSizeSp * fontMetricsRatio * gapFactor).sp.toPx()
    }
    // 参考实现的标题与正文之间固定留 0.6 倍字体度量高度
    val headingSpacingPx = with(density) {
        (settings.fontSizeSp * fontMetricsRatio * 0.6f).sp.toPx()
    }
    val pageHorizontalPadding = settings.horizontalPaddingDp.dp

    // 阅读亮度直接改宿主窗口，0 表示跟随系统；离开阅读页时还原原亮度。
    val context = LocalContext.current
    DisposableEffect(settings.brightness) {
        val window = context.findActivity()?.window ?: return@DisposableEffect onDispose {}
        val params = window.attributes
        val previous = params.screenBrightness
        params.screenBrightness = if (settings.brightness <= 0.02f) {
            -1f
        } else {
            settings.brightness
        }
        window.attributes = params
        onDispose {
            params.screenBrightness = previous
            window.attributes = params
        }
    }

    var barsVisible by remember { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var pager by remember { mutableStateOf<ReaderPager?>(null) }

    val latestPosition = rememberUpdatedState(state.positionChapter to state.positionOffset)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background)
            .safeDrawingPadding(),
    ) {
        val outerWidthPx = with(density) { maxWidth.toPx() }
        val outerHeightPx = with(density) { maxHeight.toPx() }
        val contentWidthPx = (outerWidthPx - with(density) { (pageHorizontalPadding * 2).toPx() }).toInt()
        val contentHeightPx = (
            outerHeightPx -
                with(density) { PageTopPadding.toPx() } -
                with(density) { PageBottomPadding.toPx() } -
                with(density) { PageFooterHeight.toPx() }
            ).toInt()

        LaunchedEffect(
            contentWidthPx,
            contentHeightPx,
            bodyStyle,
            headingStyle,
            paragraphSpacingPx,
            headingSpacingPx,
            settings.firstLineIndent,
            content,
            textMeasurer,
        ) {
            if (contentWidthPx <= 0 || contentHeightPx <= 0) return@LaunchedEffect
            val paginator = ChapterPaginator(
                textMeasurer = textMeasurer,
                bodyStyle = bodyStyle,
                headingStyle = headingStyle,
                widthPx = contentWidthPx,
                heightPx = contentHeightPx,
                paragraphSpacingPx = paragraphSpacingPx,
                headingSpacingPx = headingSpacingPx,
                imageAspect = content::imageAspect,
                firstLineIndent = settings.firstLineIndent,
            )
            val created = ReaderPager(content = content, paginator = paginator)
            val (chapter, offset) = latestPosition.value
            if (!created.open(chapterIndex = chapter, charOffset = offset)) {
                created.open(chapterIndex = 0, charOffset = 0)
            }
            pager = created
            revision++
        }

        fun notifyPosition() {
            val current = pager ?: return
            val page = current.currentPage
            viewModel.onAction(
                ReaderAction.OnPageChanged(
                    chapterIndex = current.chapterIndex,
                    charOffset = page?.startOffset ?: 0,
                    percent = current.bookPercent,
                    reachedEnd = current.isAtBookEnd,
                ),
            )
        }

        fun turnNext() {
            val current = pager ?: return
            if (current.goNext()) {
                revision++
                notifyPosition()
            } else {
                barsVisible = true
            }
        }

        fun turnPrevious() {
            val current = pager ?: return
            if (current.goPrevious()) {
                revision++
                notifyPosition()
            }
        }

        fun jumpToChapter(index: Int) {
            val current = pager ?: return
            if (current.jumpToChapter(index)) {
                revision++
                notifyPosition()
            }
        }

        fun jumpToBookmark(chapterIndex: Int, charOffset: Int) {
            val current = pager ?: return
            if (current.open(chapterIndex = chapterIndex, charOffset = charOffset)) {
                revision++
                notifyPosition()
            }
        }

        fun jumpToPageInChapter(progress: Float) {
            val current = pager ?: return
            if (current.jumpToPageInChapter(progress)) {
                revision++
                notifyPosition()
            }
        }

        fun jumpToBookPercent(percent: Float) {
            val current = pager ?: return
            if (current.jumpToPercent(percent)) {
                revision++
                notifyPosition()
            }
        }

        fun jumpToPreviousChapter() {
            val current = pager ?: return
            if (current.chapterIndex > 0) jumpToChapter(current.chapterIndex - 1)
        }

        fun jumpToNextChapter() {
            val current = pager ?: return
            jumpToChapter(current.chapterIndex + 1)
        }

        val snapshot = remember(revision, pager) {
            pager?.let { current ->
                ReaderSnapshot(
                    page = current.currentPage,
                    chapterIndex = current.chapterIndex,
                    pageIndex = current.pageIndex,
                    pageCount = current.pages.size,
                    chapterTitle = current.chapterTitle,
                    percent = current.bookPercent,
                )
            }
        }

        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
        LaunchedEffect(showToc, showSettings) {
            if (!showToc && !showSettings) focusRequester.requestFocus()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft, Key.PageUp -> {
                            turnPrevious()
                            true
                        }

                        Key.DirectionRight, Key.PageDown, Key.Spacebar -> {
                            turnNext()
                            true
                        }

                        else -> false
                    }
                }
                .focusable()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val width = size.width.toFloat()
                        when {
                            offset.x < width * 0.28f -> turnPrevious()
                            offset.x > width * 0.72f -> turnNext()
                            else -> barsVisible = !barsVisible
                        }
                    }
                }
                .pointerInput(settings.pageTurn) {
                    if (settings.pageTurn == ReaderPageTurn.VERTICAL) {
                        var totalDrag = 0f
                        detectVerticalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onDragCancel = { totalDrag = 0f },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                totalDrag += dragAmount
                            },
                            onDragEnd = {
                                val threshold = 64.dp.toPx()
                                when {
                                    totalDrag < -threshold -> turnNext()
                                    totalDrag > threshold -> turnPrevious()
                                }
                                totalDrag = 0f
                            },
                        )
                    } else {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onDragCancel = { totalDrag = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                totalDrag += dragAmount
                            },
                            onDragEnd = {
                                val threshold = 64.dp.toPx()
                                when {
                                    totalDrag < -threshold -> turnNext()
                                    totalDrag > threshold -> turnPrevious()
                                }
                                totalDrag = 0f
                            },
                        )
                    }
                },
        ) {
            AnimatedContent(
                targetState = snapshot,
                transitionSpec = {
                    val fromChapter = initialState?.chapterIndex ?: 0
                    val fromPage = initialState?.pageIndex ?: 0
                    val toChapter = targetState?.chapterIndex ?: 0
                    val toPage = targetState?.pageIndex ?: 0
                    val forward = toChapter > fromChapter || (toChapter == fromChapter && toPage >= fromPage)
                    when (settings.pageTurn) {
                        ReaderPageTurn.NONE ->
                            EnterTransition.None togetherWith ExitTransition.None

                        ReaderPageTurn.COVER -> {
                            // 新页从翻页方向盖上来，旧页保持不动。
                            slideInHorizontally(animationSpec = tween(260)) { width ->
                                if (forward) width else -width
                            } togetherWith ExitTransition.None
                        }

                        ReaderPageTurn.VERTICAL -> {
                            (
                                slideInVertically(animationSpec = tween(260)) { height ->
                                    if (forward) height else -height
                                }
                                ) togetherWith (
                                slideOutVertically(animationSpec = tween(260)) { height ->
                                    if (forward) -height else height
                                }
                                )
                        }

                        ReaderPageTurn.SLIDE -> {
                            if (forward) {
                                (
                                    slideInHorizontally(animationSpec = tween(240)) { it } +
                                        fadeIn(animationSpec = tween(180))
                                    ) togetherWith (
                                    slideOutHorizontally(animationSpec = tween(240)) { -it } +
                                        fadeOut(animationSpec = tween(180))
                                    )
                            } else {
                                (
                                    slideInHorizontally(animationSpec = tween(240)) { -it } +
                                        fadeIn(animationSpec = tween(180))
                                    ) togetherWith (
                                    slideOutHorizontally(animationSpec = tween(240)) { it } +
                                        fadeOut(animationSpec = tween(180))
                                    )
                            }
                        }
                    }
                },
                label = "reader_page",
                modifier = Modifier.fillMaxSize(),
            ) { target ->
                ReaderPageLayout(
                    snapshot = target,
                    content = content,
                    theme = theme,
                    bodyStyle = bodyStyle,
                    headingStyle = headingStyle,
                    paragraphSpacing = with(density) { paragraphSpacingPx.toDp() },
                    headingSpacing = with(density) { headingSpacingPx.toDp() },
                    horizontalPadding = pageHorizontalPadding,
                    firstLineIndent = settings.firstLineIndent,
                    showChapterTitle = settings.showChapterTitle,
                    showTimeBattery = settings.showTimeBattery,
                )
            }

            val currentPage = snapshot?.page
            val currentPreview = currentPage?.blocks
                ?.firstOrNull { it is PageBlock.Text || it is PageBlock.Heading }
                ?.let { block ->
                    (block as? PageBlock.Text)?.text ?: (block as? PageBlock.Heading)?.text
                }
                .orEmpty()
            val isCurrentBookmarked = remember(snapshot, state.bookmarks) {
                val page = snapshot?.page
                page != null && state.bookmarks.any {
                    it.chapterIndex == snapshot?.chapterIndex && it.charOffset == page.startOffset
                }
            }

            ReaderTopBar(
                visible = barsVisible,
                theme = theme,
                bookTitle = state.book?.title.orEmpty(),
                chapterTitle = snapshot?.chapterTitle.orEmpty(),
                onBack = {
                    viewModel.saveNow()
                    viewModel.output.onNavigateUp()
                },
                isBookmarked = isCurrentBookmarked,
                onToggleBookmark = {
                    snapshot?.let { s ->
                        viewModel.onAction(
                            ReaderAction.ToggleBookmark(
                                chapterIndex = s.chapterIndex,
                                charOffset = s.page?.startOffset ?: 0,
                                percent = s.percent,
                                chapterTitle = s.chapterTitle,
                                previewText = currentPreview,
                            ),
                        )
                    }
                },
            )

            ReaderBottomBar(
                visible = barsVisible,
                theme = theme,
                percent = snapshot?.percent ?: state.percent,
                pageIndex = snapshot?.pageIndex ?: 0,
                pageCount = snapshot?.pageCount ?: 0,
                hasPreviousChapter = (snapshot?.chapterIndex ?: 0) > 0,
                hasNextChapter = (snapshot?.chapterIndex ?: 0) < content.chapters.lastIndex,
                progressScope = settings.progressScope,
                onSeekBook = ::jumpToBookPercent,
                onSeekChapter = ::jumpToPageInChapter,
                onPreviousChapter = ::jumpToPreviousChapter,
                onNextChapter = ::jumpToNextChapter,
                onToc = { showToc = true },
                onSettings = { showSettings = true },
            )
        }

        if (showToc) {
            ReaderTocSheet(
                theme = theme,
                content = content,
                currentChapter = snapshot?.chapterIndex ?: state.positionChapter,
                bookmarks = state.bookmarks,
                onSelectChapter = { index ->
                    showToc = false
                    jumpToChapter(index)
                },
                onOpenBookmark = { bookmark ->
                    showToc = false
                    jumpToBookmark(bookmark.chapterIndex, bookmark.charOffset)
                },
                onDeleteBookmark = { id ->
                    viewModel.onAction(ReaderAction.RemoveBookmark(id))
                },
                onDismiss = { showToc = false },
            )
        }

        if (showSettings) {
            ReaderSettingsSheet(
                theme = theme,
                settings = settings,
                customFonts = state.customFonts,
                onSettingsChange = { transform ->
                    viewModel.onAction(ReaderAction.UpdateSettings(transform))
                },
                onImportFont = { uri -> viewModel.onAction(ReaderAction.ImportFont(uri)) },
                onDeleteFont = { path -> viewModel.onAction(ReaderAction.RemoveFont(path)) },
                onDismiss = { showSettings = false },
            )
        }
    }
}

@Composable
private fun ReaderPlaceholder(
    theme: ReaderTheme,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    loading: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(color = theme.accent)
            }
            Text(
                text = message,
                style = TextStyle(color = theme.text, fontSize = 16.sp),
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onAction) {
                Text(text = actionLabel, color = theme.accent)
            }
        }
    }
}

private data class ReaderSnapshot(
    val page: ReaderPage?,
    val chapterIndex: Int,
    val pageIndex: Int,
    val pageCount: Int,
    val chapterTitle: String,
    val percent: Float,
)

@Composable
private fun ReaderPageLayout(
    snapshot: ReaderSnapshot?,
    content: BookContent,
    theme: ReaderTheme,
    bodyStyle: TextStyle,
    headingStyle: TextStyle,
    paragraphSpacing: Dp,
    headingSpacing: Dp,
    horizontalPadding: Dp,
    firstLineIndent: Boolean,
    showChapterTitle: Boolean,
    showTimeBattery: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = PageTopPadding,
                    bottom = PageBottomPadding + PageFooterHeight,
                )
                .clipToBounds(),
        ) {
            snapshot?.page?.blocks?.forEach { block ->
                when (block) {
                    is PageBlock.Heading -> {
                        Text(
                            text = block.text,
                            style = headingStyle,
                            modifier = Modifier.padding(bottom = headingSpacing),
                        )
                    }

                    is PageBlock.Text -> {
                        val annotated = remember(block.text, block.indent, firstLineIndent) {
                            if (block.indent && firstLineIndent) {
                                AnnotatedString(
                                    text = block.text,
                                    paragraphStyle = ParagraphStyle(
                                        textIndent = TextIndent(firstLine = 2.em),
                                    ),
                                )
                            } else {
                                AnnotatedString(text = block.text)
                            }
                        }
                        Text(text = annotated, style = bodyStyle)
                        Spacer(modifier = Modifier.height(paragraphSpacing))
                    }

                    is PageBlock.Image -> {
                        BookImageBlock(path = block.path, heightPx = block.heightPx, content = content)
                        Spacer(modifier = Modifier.height(paragraphSpacing))
                    }

                    PageBlock.Divider -> {
                        Text(
                            text = "· · ·",
                            style = bodyStyle.copy(
                                color = theme.secondaryText,
                                fontSize = (bodyStyle.fontSize.value * 0.8f).sp,
                            ),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(paragraphSpacing))
                    }
                }
            }
        }

        if (snapshot != null) {
            if (showChapterTitle) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(start = horizontalPadding, end = horizontalPadding, top = 8.dp)
                        .height(PageTopPadding - 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = snapshot.chapterTitle,
                        style = TextStyle(
                            color = theme.secondaryText.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        bottom = PageBottomPadding,
                    )
                    .height(PageFooterHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${snapshot.pageIndex + 1} / ${snapshot.pageCount}",
                    style = TextStyle(color = theme.secondaryText.copy(alpha = 0.75f), fontSize = 11.sp),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (showTimeBattery) {
                    Text(
                        text = readerStatusText(enabled = true),
                        style = TextStyle(color = theme.secondaryText.copy(alpha = 0.75f), fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 页脚时间与电量，按分钟刷新。 */
@Composable
private fun readerStatusText(enabled: Boolean): String {
    val context = LocalContext.current
    val status by produceState(initialValue = "", enabled) {
        if (!enabled) {
            value = ""
            return@produceState
        }
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            val time = formatter.format(Date())
            val percent = runCatching {
                battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }.getOrNull() ?: -1
            value = if (percent >= 0) "$time  $percent%" else time
            delay(30_000L)
        }
    }
    return status
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (true) {
        current = when (current) {
            is Activity -> return current
            is ContextWrapper -> current.baseContext
            else -> return null
        }
    }
}

@Composable
private fun BookImageBlock(path: String, heightPx: Int, content: BookContent) {
    val density = LocalDensity.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path, heightPx) {
        value = withContext(Dispatchers.IO) {
            decodeScaledBitmap(file = content.imageFile(path), targetHeightPx = heightPx)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { heightPx.toDp() }),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private fun decodeScaledBitmap(file: File, targetHeightPx: Int): ImageBitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    val target = targetHeightPx.coerceAtLeast(1)
    while (bounds.outHeight / (sampleSize * 2) >= target) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return runCatching {
        BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
    }.getOrNull()
}

@Composable
private fun BoxScope.ReaderTopBar(
    visible: Boolean,
    theme: ReaderTheme,
    bookTitle: String,
    chapterTitle: String,
    onBack: () -> Unit,
    isBookmarked: Boolean,
    onToggleBookmark: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = slideInVertically(animationSpec = tween(200)) { -it } + fadeIn(tween(200)),
        exit = slideOutVertically(animationSpec = tween(200)) { -it } + fadeOut(tween(200)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.background.copy(alpha = 0.97f))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = NextIcons.ArrowBack, contentDescription = null, tint = theme.text)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bookTitle,
                    style = TextStyle(color = theme.text, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = chapterTitle,
                    style = TextStyle(color = theme.secondaryText, fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    imageVector = if (isBookmarked) NextIcons.Bookmark else NextIcons.BookmarkBorder,
                    contentDescription = stringResource(
                        if (isBookmarked) R.string.reader_bookmark_remove else R.string.reader_bookmark_add,
                    ),
                    tint = if (isBookmarked) theme.accent else theme.text,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.ReaderBottomBar(
    visible: Boolean,
    theme: ReaderTheme,
    percent: Float,
    pageIndex: Int,
    pageCount: Int,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    progressScope: ReaderProgressScope,
    onSeekBook: (Float) -> Unit,
    onSeekChapter: (Float) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onToc: () -> Unit,
    onSettings: () -> Unit,
) {
    val chapterProgress = if (pageCount > 1) pageIndex.toFloat() / (pageCount - 1) else 0f
    var sliderValue by remember(progressScope) {
        mutableFloatStateOf(if (progressScope == ReaderProgressScope.BOOK) percent else chapterProgress)
    }
    LaunchedEffect(progressScope, percent, chapterProgress) {
        sliderValue = if (progressScope == ReaderProgressScope.BOOK) percent else chapterProgress
    }

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(animationSpec = tween(200)) { it } + fadeIn(tween(200)),
        exit = slideOutVertically(animationSpec = tween(200)) { it } + fadeOut(tween(200)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.background.copy(alpha = 0.97f))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val shownPercent = if (progressScope == ReaderProgressScope.BOOK) {
                    sliderValue
                } else {
                    percent
                }
                Text(
                    text = "${(shownPercent * 100).roundToInt()}%",
                    style = TextStyle(color = theme.secondaryText, fontSize = 12.sp),
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        if (progressScope == ReaderProgressScope.BOOK) {
                            onSeekBook(sliderValue)
                        } else {
                            onSeekChapter(sliderValue)
                        }
                    },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = theme.accent,
                        activeTrackColor = theme.accent,
                        inactiveTrackColor = theme.secondaryText.copy(alpha = 0.35f),
                    ),
                    modifier = Modifier.weight(1f),
                )
                val shownPage = if (progressScope == ReaderProgressScope.CHAPTER && pageCount > 0) {
                    (sliderValue * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1) + 1
                } else {
                    pageIndex + 1
                }
                Text(
                    text = if (pageCount > 0) "$shownPage/$pageCount" else "",
                    style = TextStyle(color = theme.secondaryText, fontSize = 12.sp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = onPreviousChapter,
                    enabled = hasPreviousChapter,
                ) {
                    Icon(
                        imageVector = NextIcons.ChevronLeft,
                        contentDescription = null,
                        tint = theme.text.copy(alpha = if (hasPreviousChapter) 1f else 0.35f),
                    )
                }
                TextButton(onClick = onToc) {
                    Icon(
                        imageVector = NextIcons.Toc,
                        contentDescription = null,
                        tint = theme.text,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.reader_toc), color = theme.text)
                }
                TextButton(onClick = onSettings) {
                    Icon(
                        imageVector = NextIcons.Settings,
                        contentDescription = null,
                        tint = theme.text,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.reader_settings), color = theme.text)
                }
                IconButton(
                    onClick = onNextChapter,
                    enabled = hasNextChapter,
                ) {
                    Icon(
                        imageVector = NextIcons.ChevronRight,
                        contentDescription = null,
                        tint = theme.text.copy(alpha = if (hasNextChapter) 1f else 0.35f),
                    )
                }
            }
        }
    }
}

private val PageTopPadding = 36.dp
private val PageBottomPadding = 30.dp
private val PageFooterHeight = 22.dp
