package dev.anilbeesetti.nextplayer.navigation

import androidx.compose.runtime.mutableIntStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import dev.anilbeesetti.nextplayer.feature.more.navigation.MoreRoute
import dev.anilbeesetti.nextplayer.feature.playlist.navigation.PlaylistDetailRoute
import dev.anilbeesetti.nextplayer.feature.playlist.navigation.PlaylistListRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelNavigationTest {

    @Test
    fun topLevelDestinationsAreInOrder() {
        assertEquals(
            listOf(
                TopLevelDestination.MEDIA,
                TopLevelDestination.MUSIC,
                TopLevelDestination.IMAGES,
                TopLevelDestination.MORE,
            ),
            TopLevelDestination.entries,
        )
    }

    @Test
    fun switchingTabsPreservesPlaylistDetailInMoreStack() {
        val stacks = TopLevelDestination.entries.associate { destination ->
            destination.route to NavBackStack<NavKey>(destination.route)
        }
        val state = TopLevelNavState(
            destinations = TopLevelDestination.entries,
            backStacks = stacks,
            selectedIndexState = mutableIntStateOf(value = 0),
        )
        val moreStack = stacks.getValue(TopLevelDestination.MORE.route)

        state.switchTo(TopLevelDestination.MORE.route)
        moreStack += PlaylistListRoute
        moreStack += PlaylistDetailRoute(playlistId = 7)
        state.switchTo(TopLevelDestination.MEDIA.route)
        state.switchTo(TopLevelDestination.MORE.route)

        assertEquals(
            listOf(
                MoreRoute,
                PlaylistListRoute,
                PlaylistDetailRoute(playlistId = 7),
            ),
            state.currentStack,
        )
    }

    @Test
    fun shortcutSelectsPlaylistAndReplacesPreviousMoreStack() {
        val stacks = TopLevelDestination.entries.associate { destination ->
            destination.route to NavBackStack<NavKey>(destination.route)
        }
        val state = TopLevelNavState(
            destinations = TopLevelDestination.entries,
            backStacks = stacks,
            selectedIndexState = mutableIntStateOf(value = 0),
        )
        stacks.getValue(
            TopLevelDestination.MORE.route,
        ).add(element = PlaylistDetailRoute(playlistId = 7))
        state.switchTo(TopLevelDestination.MORE.route)

        state.openPlaylist(playlistId = 42)
        state.openPlaylist(playlistId = 42)

        assertEquals(
            TopLevelDestination.MORE.route,
            state.topLevelRoute,
        )
        assertEquals(
            listOf(
                MoreRoute,
                PlaylistListRoute,
                PlaylistDetailRoute(playlistId = 42),
            ),
            state.currentStack,
        )
        state.goBack()
        assertEquals(
            listOf(
                MoreRoute,
                PlaylistListRoute,
            ),
            state.currentStack,
        )
        state.goBack()
        assertEquals(
            listOf<NavKey>(MoreRoute),
            state.currentStack,
        )
        state.goBack()
        assertEquals(
            TopLevelDestination.MEDIA.route,
            state.topLevelRoute,
        )
    }

    @Test
    fun topLevelContentKeysContainsDestinations() {
        val stacks = TopLevelDestination.entries.associate { destination ->
            destination.route to NavBackStack<NavKey>(destination.route)
        }
        val state = TopLevelNavState(
            destinations = TopLevelDestination.entries,
            backStacks = stacks,
            selectedIndexState = mutableIntStateOf(value = 0),
        )
        val provider = entryProvider<NavKey> {
            TopLevelDestination.entries.forEach { destination ->
                entry(key = destination.route) {}
            }
        }
        for (dest in TopLevelDestination.entries) {
            assertTrue(state.topLevelContentKeys.contains(dest.route))
            assertTrue(state.topLevelContentKeys.contains(provider(dest.route).contentKey))
        }
    }
}
