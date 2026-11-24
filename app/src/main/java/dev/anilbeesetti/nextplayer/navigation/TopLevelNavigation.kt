package dev.anilbeesetti.nextplayer.navigation

import android.os.Build
import android.view.RoundedCorner
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import dev.anilbeesetti.nextplayer.core.common.extensions.isTelevision
import dev.anilbeesetti.nextplayer.core.model.MediaLibraryTab
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.TopLevelFabKey
import dev.anilbeesetti.nextplayer.core.ui.components.TopLevelFabState
import dev.anilbeesetti.nextplayer.core.ui.components.thenIf
import dev.anilbeesetti.nextplayer.core.ui.components.tvFocusRing
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.books.navigation.BookshelfRoute
import dev.anilbeesetti.nextplayer.feature.more.navigation.MoreRoute
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.ImagesRoute
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.MediaPickerRoute
import dev.anilbeesetti.nextplayer.feature.videopicker.navigation.MusicRoute
import kotlin.math.abs

/**
 * Top-level destinations shown in the bottom bar / nav rail. The first entry is the start (exit)
 * destination — pressing back from any other tab returns here before leaving the app.
 */
enum class TopLevelDestination(
    val route: NavKey,
    val icon: ImageVector,
    @StringRes val labelRes: Int,
    val fabKey: String? = null,
) {
    MEDIA(
        MediaPickerRoute(),
        NextIcons.Video,
        R.string.nav_videos,
        TopLevelFabKey.MEDIA,
    ),
    MUSIC(
        MusicRoute,
        NextIcons.Audio,
        R.string.music,
        TopLevelFabKey.MUSIC,
    ),
    IMAGES(
        ImagesRoute,
        NextIcons.Image,
        R.string.images,
        TopLevelFabKey.IMAGES,
    ),
    BOOKS(
        BookshelfRoute,
        NextIcons.Books,
        R.string.books,
        TopLevelFabKey.BOOKS,
    ),
    MORE(
        MoreRoute,
        NextIcons.More,
        R.string.more,
        TopLevelFabKey.MORE,
    ),
}

/** 清洗保存的底栏顺序：过滤未知/重复项、补齐缺失项，并始终将「更多」放在最后。 */
internal fun sanitizeTopLevelDestinationOrder(
    order: List<String>,
): List<TopLevelDestination> {
    val requested = order.mapNotNull { name ->
        TopLevelDestination.entries.firstOrNull { it.name == name }
    }.distinct().filterNot { it == TopLevelDestination.MORE }
    val missing = TopLevelDestination.entries.filterNot {
        it == TopLevelDestination.MORE || it in requested
    }
    return requested + missing + TopLevelDestination.MORE
}

/** 移动底栏目标；「更多」不可拖动，也不能作为移动目标。 */
internal fun List<TopLevelDestination>.moveDestination(
    fromIndex: Int,
    toIndex: Int,
): List<TopLevelDestination> {
    if (fromIndex == toIndex ||
        fromIndex !in indices ||
        toIndex !in indices ||
        this[fromIndex] == TopLevelDestination.MORE ||
        this[toIndex] == TopLevelDestination.MORE
    ) {
        return this
    }
    return toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}

@Composable
fun rememberTopLevelNavState(
    destinationOrder: List<String> = emptyList(),
): TopLevelNavState {
    val destinations = sanitizeTopLevelDestinationOrder(destinationOrder)
    // Each tab keeps its own back stack; rememberNavBackStack persists it across config change and
    // process death. Composition order stays fixed even when the visible destinations are reordered.
    val backStacks = TopLevelDestination.entries.associate { dest ->
        val backStack = rememberNavBackStack(dest.route)
        backStack.ensureRoot(dest.route)
        dest.route to backStack
    }
    // Null follows the first destination, so a persisted custom order also controls the start page.
    val selectedDestinationName = rememberSaveable { mutableStateOf<String?>(value = null) }
    return remember(
        destinations,
        backStacks,
        selectedDestinationName,
    ) {
        TopLevelNavState(
            destinations = destinations,
            backStacks = backStacks,
            selectedDestinationNameState = selectedDestinationName,
        )
    }
}

/**
 * Holds the per-tab back stacks and the currently selected tab, and flattens them into the single
 * list of entries that [androidx.navigation3.ui.NavDisplay] renders.
 *
 * The start tab's stack is always kept at the base, so a non-start tab is displayed *on top* of it;
 * this makes back navigation from a secondary tab fall through to the start tab.
 */
@Stable
class TopLevelNavState(
    val destinations: List<TopLevelDestination>,
    val backStacks: Map<NavKey, NavBackStack<NavKey>>,
    private val selectedDestinationNameState: MutableState<String?>,
) {
    private val selectedDestination: TopLevelDestination
        get() = destinations.firstOrNull { it.name == selectedDestinationNameState.value }
            ?: destinations.first()

    val selectedIndex: Int get() = destinations.indexOf(selectedDestination)

    private val startRoute: NavKey get() = destinations.first().route

    val topLevelRoute: NavKey get() = selectedDestination.route

    /** The back stack of the currently selected tab — navigation targets are added here. */
    val currentStack: NavBackStack<NavKey> get() = backStacks.getValue(key = topLevelRoute)

    private val stacksInUse: List<NavKey>
        get() = if (selectedIndex == 0) {
            listOf(element = startRoute)
        } else {
            listOf(
                startRoute,
                topLevelRoute,
            )
        }

    /** Raw routes and Navigation 3 content keys for the top-level destinations. */
    val topLevelContentKeys: Set<Any> = destinations.flatMap { dest ->
        listOf(
            dest.route,
            NavEntry(key = dest.route) {}.contentKey,
        )
    }.toSet()

    fun switchTo(route: NavKey) {
        val destination = destinations.firstOrNull { it.route == route } ?: return
        selectedDestinationNameState.value = destination.name
    }

    /** Keep the visible destination stable when dragging changes the destination indices. */
    fun pinSelectedDestination() {
        selectedDestinationNameState.value = selectedDestination.name
    }

    fun goBack() {
        val stack = currentStack
        when {
            stack.size > 1 -> stack.removeLastOrNull()
            selectedIndex != 0 -> selectedDestinationNameState.value = destinations.first().name
        }
    }

    @Composable
    fun rememberEntries(
        entryProvider: (NavKey) -> NavEntry<NavKey>,
        entryDecorator: NavEntryDecorator<NavKey>,
    ): SnapshotStateList<NavEntry<NavKey>> {
        val decoratedByRoute = LinkedHashMap<NavKey, List<NavEntry<NavKey>>>()
        for (dest in TopLevelDestination.entries) {
            decoratedByRoute[dest.route] = rememberDecoratedNavEntries(
                backStack = backStacks.getValue(dest.route),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                    entryDecorator,
                ),
                entryProvider = entryProvider,
            )
        }
        return stacksInUse.flatMap { decoratedByRoute.getValue(key = it) }.toMutableStateList()
    }
}

fun TopLevelNavState.isNavigationBetweenTopLevelDestinations(
    initialState: Scene<NavKey>,
    targetState: Scene<NavKey>,
): Boolean = topLevelContentKeys.run {
    contains(initialState.entries.lastOrNull()?.contentKey) &&
        contains(targetState.entries.lastOrNull()?.contentKey)
}

// Timings measured from the system activity transition (ColorOS 16 Settings), which apps can't
// borrow directly for in-activity navigation.
private const val PUSH_DURATION_MILLIS = 320
private const val POP_DURATION_MILLIS = 360
private val PushEasing = CubicBezierEasing(
    0.3f,
    0.7f,
    0.5f,
    1f,
)
private val PopEasing = CubicBezierEasing(
    0.3f,
    0.3f,
    0.3f,
    1f,
)
private const val PARALLAX_FRACTION = 0.3f
private const val PREDICTIVE_PARALLAX_FRACTION = 0.25f
private const val MAX_SCRIM_ALPHA = 0.5f

// Quick Back can select the pop spec while the animated scenes still describe the push.
// Keep the direction tied to those scenes so the slide can reverse smoothly.
private fun Scene<NavKey>.isPoppingTo(targetState: Scene<NavKey>): Boolean = previousEntries.any {
    it.contentKey == targetState.entries.lastOrNull()?.contentKey
}

internal fun TopLevelNavState.navigationTransition(
    initialState: Scene<NavKey>,
    targetState: Scene<NavKey>,
    isPredictive: Boolean = false,
): ContentTransform {
    if (isNavigationBetweenTopLevelDestinations(
            initialState = initialState,
            targetState = targetState,
        )
    ) {
        return fadeIn(
            animationSpec = tween(
                durationMillis = 200,
                easing = LinearEasing,
            ),
        ) togetherWith
            fadeOut(
                animationSpec = tween(
                    durationMillis = 200,
                    easing = LinearEasing,
                ),
            )
    }

    val isPop = initialState.isPoppingTo(targetState)
    // Predictive back seeks this transition with the gesture progress, so keep it linear to track
    // the finger.
    val animationSpec = when {
        isPredictive -> tween<IntOffset>(
            durationMillis = POP_DURATION_MILLIS,
            easing = LinearEasing,
        )

        isPop -> tween(
            durationMillis = POP_DURATION_MILLIS,
            easing = PopEasing,
        )

        else -> tween(
            durationMillis = PUSH_DURATION_MILLIS,
            easing = PushEasing,
        )
    }
    val parallax = if (isPredictive) PREDICTIVE_PARALLAX_FRACTION else PARALLAX_FRACTION
    return slideInHorizontally(
        initialOffsetX = { if (isPop) -(it * parallax).toInt() else it },
        animationSpec = animationSpec,
    ) togetherWith slideOutHorizontally(
        targetOffsetX = { if (isPop) it else -(it * parallax).toInt() },
        animationSpec = animationSpec,
    )
}

/**
 * Matches the system activity transition: dims the screen underneath a push or pop and clips the
 * screen on top to the display's rounded corners, when the system reports them.
 */
@Composable
internal fun TopLevelNavState.navigationTransitionModifier(entry: NavEntry<NavKey>): Modifier {
    val visibility = LocalNavAnimatedContentScope.current.transition

    @Suppress("UNCHECKED_CAST")
    val scenes = visibility.parentTransition as? Transition<Scene<NavKey>> ?: return Modifier
    val initialState = scenes.currentState
    val targetState = scenes.targetState
    val isPop = initialState.isPoppingTo(targetState)
    val scrimAlpha = visibility.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = if (isPop) POP_DURATION_MILLIS else PUSH_DURATION_MILLIS,
                easing = LinearEasing,
            )
        },
        label = "navigation_scrim",
    ) { if (it == EnterExitState.Visible) 0f else MAX_SCRIM_ALPHA }
    val cornerShape = rememberDisplayCornerShape()

    if (initialState == targetState ||
        isNavigationBetweenTopLevelDestinations(
            initialState,
            targetState,
        )
    ) {
        return Modifier
    }
    val bottomScene = if (isPop) targetState else initialState
    return if (entry.contentKey == bottomScene.entries.lastOrNull()?.contentKey) {
        Modifier.drawWithContent {
            drawContent()
            drawRect(
                color = Color.Black,
                alpha = scrimAlpha.value,
            )
        }
    } else {
        Modifier.thenIf(cornerShape != null) { clip(cornerShape!!) }
    }
}

@Composable
private fun rememberDisplayCornerShape(): Shape? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val insets = LocalView.current.rootWindowInsets ?: return null
    return remember(insets) {
        fun radius(position: Int) = insets.getRoundedCorner(position)?.radius?.toFloat() ?: 0f
        val topLeft = radius(RoundedCorner.POSITION_TOP_LEFT)
        val topRight = radius(RoundedCorner.POSITION_TOP_RIGHT)
        val bottomRight = radius(RoundedCorner.POSITION_BOTTOM_RIGHT)
        val bottomLeft = radius(RoundedCorner.POSITION_BOTTOM_LEFT)
        if (topLeft + topRight + bottomRight + bottomLeft == 0f) {
            null
        } else {
            AbsoluteRoundedCornerShape(
                topLeft = topLeft,
                topRight = topRight,
                bottomRight = bottomRight,
                bottomLeft = bottomLeft,
            )
        }
    }
}

/**
 * 全宽胶囊导航条高度：64dp 外容器减去上下各 4dp 内边距，槽位净高 56dp，
 * 与 M3 FloatingActionButton 默认容器等高。
 */
private val NavigationBarHeight = 64.dp

/**
 * FAB 悬浮后与底栏胶囊之间的垂直间距。
 * 取 16dp 与外层 horizontal padding 一致，使 FAB 距底栏和距屏幕右边的间距相等。
 */
private val FabGapAboveBar = 16.dp

/** 图片/漫画复合 tab 的图标：跟随图片库内部排序里的第一个 tab。 */
private fun TopLevelDestination.iconFor(firstImageTab: MediaLibraryTab): ImageVector =
    if (this == TopLevelDestination.IMAGES) {
        if (firstImageTab == MediaLibraryTab.COMICS) NextIcons.Comic else NextIcons.Image
    } else {
        icon
    }

/** 图片/漫画复合 tab 的标签：跟随图片库内部排序里的第一个 tab。 */
private fun TopLevelDestination.labelFor(firstImageTab: MediaLibraryTab): Int =
    if (this == TopLevelDestination.IMAGES) {
        if (firstImageTab == MediaLibraryTab.COMICS) R.string.comics else R.string.images
    } else {
        labelRes
    }

@Composable
fun NextNavigationBar(
    state: TopLevelNavState,
    fabState: TopLevelFabState?,
    contentFocusRequester: FocusRequester,
    fabFocusRequester: FocusRequester,
    imageLibraryFirstTab: MediaLibraryTab,
    onMoveDestination: (Int, Int) -> Unit = { _, _ -> },
    showFabOnly: Boolean = false,
) {
    val isTv = LocalContext.current.isTelevision
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .thenIf(condition = !showFabOnly) {
                background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                )
            }
            .navigationBarsPadding()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .padding(bottom = 12.dp)
            .padding(horizontal = 16.dp)
            .thenIf(condition = !showFabOnly) { pointerInput(key1 = Unit) {} },
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (!showFabOnly) {
            NavigationView(
                destinations = state.destinations,
                imageLibraryFirstTab = imageLibraryFirstTab,
                isSelected = { it.route == state.topLevelRoute },
                onDestinationClick = { state.switchTo(it.route) },
                onDestinationDragStart = state::pinSelectedDestination,
                onMoveDestination = onMoveDestination,
                modifier = Modifier.align(alignment = Alignment.BottomCenter),
            )
        }

        fabState?.let { fab ->
            FloatingActionButton(
                onClick = fab.onClick,
                modifier = Modifier
                    .align(alignment = Alignment.BottomEnd)
                    // 手机端 FAB 悬浮在全宽胶囊上方 30dp；offset 只平移视觉与触摸位置，
                    // 布局占位不变。平板 showFabOnly 无胶囊，保持右下角原位。
                    .thenIf(condition = !showFabOnly) {
                        offset(y = -(NavigationBarHeight + FabGapAboveBar))
                    }
                    .testTag(tag = "top_level_fab")
                    .focusRequester(focusRequester = fabFocusRequester)
                    .tvFocusRing(shape = MaterialTheme.shapes.large)
                    .focusProperties {
                        if (isTv) {
                            up =
                                fab.upFocusRequester.takeUnless { it == FocusRequester.Default }
                                    ?: contentFocusRequester
                        }
                    },
                shape = MaterialTheme.shapes.large,
            ) {
                AnimatedContent(
                    targetState = fab,
                    transitionSpec = {
                        fadeIn(
                            animationSpec = tween(
                                durationMillis = 250,
                                easing = FastOutSlowInEasing,
                            ),
                        ) +
                            scaleIn(
                                animationSpec = tween(
                                    durationMillis = 250,
                                    easing = FastOutSlowInEasing,
                                ),
                                initialScale = 0.25f,
                            ) togetherWith
                            fadeOut(
                                animationSpec = tween(
                                    durationMillis = 250,
                                    easing = FastOutSlowInEasing,
                                ),
                            ) +
                            scaleOut(
                                animationSpec = tween(
                                    durationMillis = 250,
                                    easing = FastOutSlowInEasing,
                                ),
                                targetScale = 0.25f,
                            )
                    },
                    label = "fab_icon_swap",
                ) { fab ->
                    Icon(
                        imageVector = fab.icon,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}

@Composable
fun NavigationView(
    modifier: Modifier = Modifier,
    destinations: List<TopLevelDestination>,
    imageLibraryFirstTab: MediaLibraryTab,
    isSelected: (TopLevelDestination) -> Boolean,
    onDestinationClick: (TopLevelDestination) -> Unit,
    onDestinationDragStart: () -> Unit = {},
    onMoveDestination: (Int, Int) -> Unit = { _, _ -> },
) {
    val animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    val boundsTransform = remember { BoundsTransform { _, _ -> animationSpec } }
    val destinationWidths = remember { mutableMapOf<TopLevelDestination, Float>() }
    var draggingDestination by remember { mutableStateOf<TopLevelDestination?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val currentDestinations by rememberUpdatedState(destinations)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height = NavigationBarHeight)
            .graphicsLayer(
                shadowElevation = with(LocalDensity.current) { 6.dp.toPx() },
                shape = CircleShape,
            )
            .clip(shape = CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(all = 4.dp),
    ) {
        LookaheadScope {
            val pill = remember {
                movableContentOf<BoxScope> { scope ->
                    with(receiver = scope) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .animateBounds(
                                    lookaheadScope = this@LookaheadScope,
                                    boundsTransform = boundsTransform,
                                )
                                .clip(shape = CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                destinations.forEach { destination ->
                    key(destination) {
                        val selected = isSelected(destination)
                        val dragging = destination == draggingDestination
                        Box(
                            modifier = Modifier
                                .weight(weight = 1f)
                                .fillMaxHeight()
                                .zIndex(
                                    zIndex = when {
                                        dragging -> 2f
                                        selected -> 0f
                                        else -> 1f
                                    },
                                )
                                .graphicsLayer {
                                    translationX = if (dragging) dragOffset else 0f
                                    alpha = if (dragging) 0.8f else 1f
                                }
                                .onGloballyPositioned { coordinates ->
                                    destinationWidths[destination] =
                                        coordinates.size.width.toFloat()
                                }
                                .thenIf(condition = destination != TopLevelDestination.MORE) {
                                    pointerInput(key1 = destination) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggingDestination = destination
                                                dragOffset = 0f
                                                onDestinationDragStart()
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffset += dragAmount.x
                                                val current = currentDestinations
                                                val currentIndex = current.indexOf(destination)
                                                if (currentIndex == -1) {
                                                    return@detectDragGesturesAfterLongPress
                                                }
                                                val neighborIndex = when {
                                                    dragOffset > 0f &&
                                                        currentIndex < current.lastIndex - 1 ->
                                                        currentIndex + 1
                                                    dragOffset < 0f && currentIndex > 0 ->
                                                        currentIndex - 1
                                                    else -> -1
                                                }
                                                if (neighborIndex == -1) {
                                                    return@detectDragGesturesAfterLongPress
                                                }
                                                val neighbor = current[neighborIndex]
                                                val step = destinationWidths[neighbor] ?: 0f
                                                if (step <= 0f) {
                                                    return@detectDragGesturesAfterLongPress
                                                }
                                                if (abs(dragOffset) >= step / 2f) {
                                                    onMoveDestination(currentIndex, neighborIndex)
                                                    dragOffset -= if (dragOffset > 0f) step else -step
                                                }
                                            },
                                            onDragEnd = {
                                                draggingDestination = null
                                                dragOffset = 0f
                                            },
                                            onDragCancel = {
                                                draggingDestination = null
                                                dragOffset = 0f
                                            },
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                pill(this)
                            }
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .tvFocusRing(shape = RoundedCornerShape(99.dp))
                                    .clip(shape = CircleShape)
                                    .selectable(
                                        selected = selected,
                                        onClick = { onDestinationClick(destination) },
                                        role = Role.Tab,
                                        indication = null,
                                        interactionSource = null,
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(
                                    space = 2.dp,
                                    alignment = Alignment.CenterVertically,
                                ),
                            ) {
                                CompositionLocalProvider(
                                    value = LocalContentColor provides if (selected) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                ) {
                                    Icon(
                                        imageVector = destination.iconFor(imageLibraryFirstTab),
                                        contentDescription = stringResource(
                                            destination.labelFor(imageLibraryFirstTab),
                                        ),
                                        modifier = Modifier.size(24.dp),
                                    )
                                    // 与参考底栏一致：只有选中项展示文字标签，未选中只留图标
                                    if (selected) {
                                        Text(
                                            text = stringResource(
                                                destination.labelFor(imageLibraryFirstTab),
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NextNavigationRail(
    state: TopLevelNavState,
    imageLibraryFirstTab: MediaLibraryTab,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        modifier = modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(
                8.dp,
                Alignment.CenterVertically,
            ),
        ) {
            state.destinations.forEach { dest ->
                val selected = state.topLevelRoute == dest.route
                Box(
                    Modifier
                        .tvFocusRing(shape = RoundedCornerShape(99.dp))
                        .clip(shape = CircleShape)
                        .selectable(
                            selected = state.topLevelRoute == dest.route,
                            onClick = { state.switchTo(dest.route) },
                            role = Role.Tab,
                        )
                        .background(
                            color = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Transparent
                            },
                        )
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                    propagateMinConstraints = true,
                ) {
                    CompositionLocalProvider(
                        value = LocalContentColor provides if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ) {
                        Icon(
                            imageVector = dest.iconFor(imageLibraryFirstTab),
                            contentDescription = stringResource(
                                dest.labelFor(imageLibraryFirstTab),
                            ),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}
