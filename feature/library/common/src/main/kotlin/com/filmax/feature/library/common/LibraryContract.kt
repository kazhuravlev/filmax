package com.filmax.feature.library.common

import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.favorites.model.FavoriteItem
import com.filmax.core.domain.user.model.BookmarkFolder
import com.filmax.core.domain.watching.model.WatchHistory
import com.filmax.core.domain.watching.model.WatchingItem

enum class LibrarySection(val title: String) {
    WATCHING("Я смотрю"),
    BOOKMARKS("Подборки"),

    HISTORY("История"),
}

data class BookmarkFolderPreview(
    val items: List<Item>,
    val endReached: Boolean,
)

data class OpenBookmarkFolder(
    val folder: BookmarkFolder,
    val items: List<Item> = emptyList(),
    val page: Int = 0,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
)

data class LibraryState(
    val favorites: List<FavoriteItem> = emptyList(),
    val watching: List<WatchingItem> = emptyList(),
    val history: List<WatchHistory> = emptyList(),
    val watchLaterRail: List<Item> = emptyList(),
    val titleDetails: Map<Int, Item> = emptyMap(),
    val lists: List<BookmarkFolder> = emptyList(),
    val folderPreviews: Map<Int, BookmarkFolderPreview> = emptyMap(),
    val loadingFolderPreviews: Set<Int> = emptySet(),
    val openFolder: OpenBookmarkFolder? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

sealed interface LibraryEvent {
    data class Refresh(val section: LibrarySection) : LibraryEvent

    data class RefreshIfDirty(val section: LibrarySection) : LibraryEvent
    data class RemoveFromHistory(val itemId: Int) : LibraryEvent
    data object ClearHistory : LibraryEvent
    data class OpenFolder(val folder: BookmarkFolder) : LibraryEvent
    data class LoadFolderPreview(val folder: BookmarkFolder) : LibraryEvent
    data object CloseFolder : LibraryEvent
    data object LoadMoreFolderItems : LibraryEvent

    data class CreateFolder(val title: String) : LibraryEvent

    data class DeleteFolder(val folderId: Int) : LibraryEvent

    data class RemoveItemFromFolder(val itemId: Int, val folderId: Int) : LibraryEvent
}

sealed interface LibrarySideEffect
