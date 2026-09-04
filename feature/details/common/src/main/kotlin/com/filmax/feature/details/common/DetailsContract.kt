package com.filmax.feature.details.common

import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.person.CastMember
import com.filmax.core.domain.user.model.BookmarkFolder
import com.filmax.core.domain.watching.model.Continuation

data class DetailsState(
    val loading: Boolean = true,
    val item: Item? = null,
    /** Рассчитан по history + tracklist, а не по одному `watchStatus` из деталей. */
    val continuation: Continuation? = null,
    val similar: List<Item> = emptyList(),
    /**
     * Актёры с фото (TMDB) — украшение поверх строки имён от kino.watch. Пустой список, когда фото
     * недоступны (нет ключа TMDB, нет совпадения по IMDb, сбой): экран падает на строку `item.cast`.
     */
    val cast: List<CastMember> = emptyList(),
    val isFav: Boolean = false,
    val isDownloaded: Boolean = false,
    /** Подборки пользователя — для выбора, куда добавить тайтл, кроме «Буду смотреть». */
    val bookmarkFolders: List<BookmarkFolder> = emptyList(),
    val error: String? = null,
)

sealed interface DetailsEvent {
    data object ToggleFav : DetailsEvent
    data object ToggleDownload : DetailsEvent

    /**
     * Отметить тайтл как «Я смотрю» — отдельная от «Буду смотреть» и от подборок серверная
     * пометка (`watching/toggle`). У неё нет читаемого состояния «включено/выключено» на уровне
     * тайтла (сервер отдаёт статус только по конкретной серии), поэтому это одноразовое
     * действие, а не переключатель.
     */
    data object ToggleWatching : DetailsEvent

    /** Добавить текущий тайтл в существующую подборку. */
    data class AddToFolder(val folderId: Int) : DetailsEvent

    /** Создать новую подборку и сразу добавить в неё текущий тайтл. */
    data class CreateFolderAndAdd(val title: String) : DetailsEvent
}

sealed interface DetailsSideEffect
