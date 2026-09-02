package com.filmax.feature.library.mobile.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.filmax.feature.library.mobile.LibraryScreen
import com.filmax.feature.library.common.LibrarySection
import kotlinx.serialization.Serializable

@Serializable
object WatchingRoute

@Serializable
object BookmarksRoute

fun NavGraphBuilder.watchingScreen() {
    composable<WatchingRoute> {
        LibraryScreen(section = LibrarySection.WATCHING)
    }
}

fun NavGraphBuilder.bookmarksScreen() {
    composable<BookmarksRoute> {
        LibraryScreen(section = LibrarySection.BOOKMARKS)
    }
}
