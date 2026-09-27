package com.filmax.feature.details.common

import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.person.CastMember
import com.filmax.core.domain.user.model.BookmarkFolder
import com.filmax.core.domain.watching.model.Continuation

data class DetailsState(
    val loading: Boolean = true,
    val item: Item? = null,
    val continuation: Continuation? = null,
    val continuationLoading: Boolean = true,
    val isWantToWatch: Boolean = false,
    val similar: List<Item> = emptyList(),
    val similarLoading: Boolean = false,
    val directorFilms: List<Item> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val isDownloaded: Boolean = false,
    val bookmarkFolders: List<BookmarkFolder> = emptyList(),
    val folderMemberships: Set<Int> = emptySet(),
    val error: String? = null,
)

sealed interface DetailsEvent {
    data object ToggleDownload : DetailsEvent

    data object ToggleWantToWatch : DetailsEvent

    data class ToggleFolder(val folder: BookmarkFolder) : DetailsEvent

    data class CreateFolderAndAdd(val title: String) : DetailsEvent

    data object PrefetchPlayback : DetailsEvent

    data object PrefetchEpisodeThumbnails : DetailsEvent
}

sealed interface DetailsSideEffect
