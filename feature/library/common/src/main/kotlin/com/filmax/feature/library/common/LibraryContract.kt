package com.filmax.feature.library.common

import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.favorites.model.FavoriteItem
import com.filmax.core.domain.user.model.BookmarkFolder
import com.filmax.core.domain.watching.model.WatchHistory
import com.filmax.core.domain.watching.model.WatchingItem

/** Два самостоятельных раздела бывшего «Моё». */
enum class LibrarySection(val title: String) {
    WATCHING("Я смотрю"),
    BOOKMARKS("Подборки"),
}

/**
 * Первая страница подборки для плитки-превью в серверном порядке.
 * При открытии она становится началом сетки, поэтому превью и содержимое совпадают.
 */
data class BookmarkFolderPreview(
    val items: List<Item>,
    val endReached: Boolean,
)

/**
 * Открытая папка-закладка вместе с её содержимым. Отдельный объект, а не плоские поля
 * состояния: содержимое без папки бессмысленно, а `null` однозначно значит «показываем
 * список папок». Содержимое постраничное — kino.watch отдаёт `bookmarks/{id}` по страницам.
 */
data class OpenBookmarkFolder(
    val folder: BookmarkFolder,
    val items: List<Item> = emptyList(),
    /** Последняя загруженная страница (0 — ещё ни одной). */
    val page: Int = 0,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
)

data class LibraryState(
    val favorites: List<FavoriteItem> = emptyList(),
    /**
     * Тайтлы «в процессе» приходят одним запросом на тип (`watching/{type}`). Полные данные
     * универсальной карточки догружаются отдельно в [titleDetails]; точной позиции здесь нет.
     */
    val watching: List<WatchingItem> = emptyList(),
    /** Полная история просмотров, отсортированная сервером от новых записей к старым. */
    val history: List<WatchHistory> = emptyList(),
    /** Полные данные для единой карточки тайтла: год, жанры, рейтинги и качественный постер. */
    val titleDetails: Map<Int, Item> = emptyMap(),
    val lists: List<BookmarkFolder> = emptyList(),
    /** Уже загруженные первые страницы для видимых плиток подборок. */
    val folderPreviews: Map<Int, BookmarkFolderPreview> = emptyMap(),
    /** Идут отдельно, чтобы одновременные рекомпозиции плитки не дублировали запрос. */
    val loadingFolderPreviews: Set<Int> = emptySet(),
    /** Папка-закладка, в которую провалились; null — показываем список папок. */
    val openFolder: OpenBookmarkFolder? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

sealed interface LibraryEvent {
    /** Повторный выбор активной вкладки: перечитать данные только её раздела. */
    data class Refresh(val section: LibrarySection) : LibraryEvent
    data class RemoveFromHistory(val itemId: Int) : LibraryEvent
    data object ClearHistory : LibraryEvent
    data class OpenFolder(val folder: BookmarkFolder) : LibraryEvent
    data class LoadFolderPreview(val folder: BookmarkFolder) : LibraryEvent
    data object CloseFolder : LibraryEvent
    data object LoadMoreFolderItems : LibraryEvent

    /** Создать новую папку-закладку с этим названием. */
    data class CreateFolder(val title: String) : LibraryEvent

    /** Удалить папку целиком. Если она открыта — экран возвращается к списку папок. */
    data class DeleteFolder(val folderId: Int) : LibraryEvent

    /** Убрать один тайтл из папки. [folderId] — папка, из которой убираем. */
    data class RemoveItemFromFolder(val itemId: Int, val folderId: Int) : LibraryEvent
}

sealed interface LibrarySideEffect
