package com.filmax.feature.library.common

import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.favorites.model.FavoriteItem
import com.filmax.core.domain.user.model.BookmarkFolder
import com.filmax.core.domain.watching.model.Continuation
import com.filmax.core.domain.watching.model.WatchHistory

/** Два самостоятельных раздела бывшего «Моё». */
enum class LibrarySection(val title: String) {
    WATCHING("Я смотрю"),
    BOOKMARKS("Закладки"),
}

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
    val history: List<WatchHistory> = emptyList(),
    /** Только реально незавершённые тайтлы; вычисляются по общей continuation-логике. */
    val continuations: List<Continuation> = emptyList(),
    val lists: List<BookmarkFolder> = emptyList(),
    /** Папка-закладка, в которую провалились; null — показываем список папок. */
    val openFolder: OpenBookmarkFolder? = null,
    /**
     * Скрыть историю просмотров на экране. Телевизор — общий экран в доме, и «что я смотрел»
     * там видно всем. Флаг живёт только пока жив процесс: постоянного хранилища настроек в
     * домене нет, поэтому UI честно говорит, что скрытие — до перезапуска.
     */
    val historyHidden: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
)

sealed interface LibraryEvent {
    data class RemoveFromHistory(val itemId: Int) : LibraryEvent
    data object ClearHistory : LibraryEvent
    data class OpenFolder(val folder: BookmarkFolder) : LibraryEvent
    data object CloseFolder : LibraryEvent
    data object LoadMoreFolderItems : LibraryEvent
    data object ToggleHistoryHidden : LibraryEvent

    /** Создать новую папку-закладку с этим названием. */
    data class CreateFolder(val title: String) : LibraryEvent

    /** Удалить папку целиком. Если она открыта — экран возвращается к списку папок. */
    data class DeleteFolder(val folderId: Int) : LibraryEvent

    /** Убрать один тайтл из папки. [folderId] — папка, из которой убираем. */
    data class RemoveItemFromFolder(val itemId: Int, val folderId: Int) : LibraryEvent
}

sealed interface LibrarySideEffect
