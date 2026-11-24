package dev.anilbeesetti.nextplayer.core.ui.composables

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 和 [androidx.compose.foundation.lazy.grid.rememberLazyGridState] 一样创建一个 [LazyGridState]，
 * 但用 [rememberSaveable] 把滚动位置（firstVisibleItemIndex + firstVisibleItemScrollOffset）
 * 持久化到 Navigation 的 SaveableStateHolder 里。这样即使整个 Composable 被上层 push/pop 掉
 * （比如 MORE tab 里的浏览页 push 阅读页后 dispose），返回时也能自动滚回去。
 *
 * 项目里所有可能被导航栈中间态 dispose 的 LazyGrid 都应该用这个，而不是裸调 rememberLazyGridState。
 *
 * @param key 用来区分同层级多个 Grid 的滚动位置；目录路径、分组 id 之类的都可以。
 */
@Composable
fun rememberPersistentLazyGridState(
    key: Any? = null,
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
): LazyGridState {
    var savedIndex by rememberSaveable(key) { mutableIntStateOf(initialFirstVisibleItemIndex) }
    var savedOffset by rememberSaveable(key) { mutableIntStateOf(initialFirstVisibleItemScrollOffset) }
    val state = remember(key) {
        LazyGridState(
            firstVisibleItemIndex = initialFirstVisibleItemIndex,
            firstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset,
        )
    }
    LaunchedEffect(key) {
        state.scrollToItem(savedIndex, savedOffset)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { current ->
                if (current.first != savedIndex || current.second != savedOffset) {
                    savedIndex = current.first
                    savedOffset = current.second
                }
            }
    }
    return state
}

/** [rememberPersistentLazyGridState] 对 [LazyListState] 的等价版本。 */
@Composable
fun rememberPersistentLazyListState(
    key: Any? = null,
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
): LazyListState {
    var savedIndex by rememberSaveable(key) { mutableIntStateOf(initialFirstVisibleItemIndex) }
    var savedOffset by rememberSaveable(key) { mutableIntStateOf(initialFirstVisibleItemScrollOffset) }
    val state = remember(key) {
        LazyListState(
            firstVisibleItemIndex = initialFirstVisibleItemIndex,
            firstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset,
        )
    }
    LaunchedEffect(key) {
        state.scrollToItem(savedIndex, savedOffset)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { current ->
                if (current.first != savedIndex || current.second != savedOffset) {
                    savedIndex = current.first
                    savedOffset = current.second
                }
            }
    }
    return state
}
