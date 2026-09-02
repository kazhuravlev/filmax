package com.filmax.feature.library.tv.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.filmax.feature.library.common.LibrarySection
import com.filmax.feature.library.tv.TvLibraryScreen
import kotlinx.serialization.Serializable

@Serializable
object TvWatchingRoute

@Serializable
object TvBookmarksRoute

/** Все карточки разделов ведут в карточку тайтла: там есть и продолжение, и выбор серий. */
fun NavGraphBuilder.tvWatchingScreen(
    onOpenItem: (Int) -> Unit,
) {
    composable<TvWatchingRoute> {
        TvLibraryScreen(section = LibrarySection.WATCHING, onOpenItem = onOpenItem)
    }
}

fun NavGraphBuilder.tvBookmarksScreen(
    onOpenItem: (Int) -> Unit,
) {
    composable<TvBookmarksRoute> {
        TvLibraryScreen(section = LibrarySection.BOOKMARKS, onOpenItem = onOpenItem)
    }
}
