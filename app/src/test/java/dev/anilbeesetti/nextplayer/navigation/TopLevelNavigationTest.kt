package dev.anilbeesetti.nextplayer.navigation

import androidx.compose.runtime.mutableStateOf
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
                TopLevelDestination.BOOKS,
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
            selectedDestinationNameState = mutableStateOf<String?>(value = null),
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
            selectedDestinationNameState = mutableStateOf<String?>(value = null),
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
    fun emptyDestinationOrderUsesDefaultOrder() {
        assertEquals(
            TopLevelDestination.entries,
            sanitizeTopLevelDestinationOrder(emptyList()),
        )
    }

    @Test
    fun savedDestinationOrderIsSanitizedAndMoreStaysLast() {
        assertEquals(
            listOf(
                TopLevelDestination.BOOKS,
                TopLevelDestination.MUSIC,
                TopLevelDestination.IMAGES,
                TopLevelDestination.MEDIA,
                TopLevelDestination.MORE,
            ),
            sanitizeTopLevelDestinationOrder(
                listOf("BOOKS", "UNKNOWN", "MUSIC", "BOOKS", "MORE", "IMAGES"),
            ),
        )
    }

    @Test
    fun moveDestinationReordersOnlySortableItems() {
        val destinations = TopLevelDestination.entries
        assertEquals(
            listOf(
                TopLevelDestination.MUSIC,
                TopLevelDestination.MEDIA,
                TopLevelDestination.IMAGES,
                TopLevelDestination.BOOKS,
                TopLevelDestination.MORE,
            ),
            destinations.moveDestination(fromIndex = 0, toIndex = 1),
        )
        assertEquals(destinations, destinations.moveDestination(fromIndex = 4, toIndex = 3))
        assertEquals(destinations, destinations.moveDestination(fromIndex = 3, toIndex = 4))
        assertEquals(destinations, destinations.moveDestination(fromIndex = -1, toIndex = 0))
        assertEquals(destinations, destinations.moveDestination(fromIndex = 0, toIndex = 5))
    }

    @Test
    fun pinningSelectionPreventsReorderFromChangingVisibleDestination() {
        val stacks = TopLevelDestination.entries.associate { destination ->
            destination.route to NavBackStack<NavKey>(destination.route)
        }
        val selection = mutableStateOf<String?>(value = null)
        val initialState = TopLevelNavState(
            destinations = TopLevelDestination.entries,
            backStacks = stacks,
            selectedDestinationNameState = selection,
        )
        initialState.pinSelectedDestination()

        val reorderedState = TopLevelNavState(
            destinations = listOf(
                TopLevelDestination.BOOKS,
                TopLevelDestination.MEDIA,
                TopLevelDestination.MUSIC,
                TopLevelDestination.IMAGES,
                TopLevelDestination.MORE,
            ),
            backStacks = stacks,
            selectedDestinationNameState = selection,
        )

        assertEquals(TopLevelDestination.MEDIA.route, reorderedState.topLevelRoute)
    }

    @Test
    fun customOrderControlsHomeAndBackNavigation() {
        val stacks = TopLevelDestination.entries.associate { destination ->
            destination.route to NavBackStack<NavKey>(destination.route)
        }
        val state = TopLevelNavState(
            destinations = listOf(
                TopLevelDestination.BOOKS,
                TopLevelDestination.MEDIA,
                TopLevelDestination.MUSIC,
                TopLevelDestination.IMAGES,
                TopLevelDestination.MORE,
            ),
            backStacks = stacks,
            selectedDestinationNameState = mutableStateOf<String?>(value = null),
        )
        assertEquals(TopLevelDestination.BOOKS.route, state.topLevelRoute)

        state.switchTo(TopLevelDestination.MUSIC.route)
        state.goBack()

        assertEquals(TopLevelDestination.BOOKS.route, state.topLevelRoute)
    }

    @Test
    fun topLevelContentKeysContainsDestinations() {
        val stacks = TopLevelDestination.entries.associate { destination ->
            destination.route to NavBackStack<NavKey>(destination.route)
        }
        val state = TopLevelNavState(
            destinations = TopLevelDestination.entries,
            backStacks = stacks,
            selectedDestinationNameState = mutableStateOf<String?>(value = null),
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
