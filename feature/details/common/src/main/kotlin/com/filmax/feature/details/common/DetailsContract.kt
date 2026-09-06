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
    /**
     * Тайтл сейчас в нативном watchlist kino.watch (`item.inWatchlist`, переключается
     * `watching/togglewatchlist`), см. [DetailsEvent.ToggleWantToWatch].
     */
    val isWantToWatch: Boolean = false,
    val similar: List<Item> = emptyList(),
    /** true, пока ответ на «Похожее» ещё не пришёл — экран рисует скелетон ряда вместо карточек,
     * не блокируя [loading] (сам тайтл уже открыт). */
    val similarLoading: Boolean = false,
    /**
     * Другие тайтлы того же (первого) режиссёра — поиском по имени (`SearchRepository.searchByDirector`),
     * без текущего тайтла в списке. Пустой, пока не пришёл ответ, или когда режиссёр не указан.
     */
    val directorFilms: List<Item> = emptyList(),
    /**
     * Актёры с фото (TMDB) — украшение поверх строки имён от kino.watch. Пустой список, когда фото
     * недоступны (нет ключа TMDB, нет совпадения по IMDb, сбой): экран падает на строку `item.cast`.
     */
    val cast: List<CastMember> = emptyList(),
    val isDownloaded: Boolean = false,
    /** Все подборки пользователя, включая «Буду смотреть» — единый список для диалога выбора. */
    val bookmarkFolders: List<BookmarkFolder> = emptyList(),
    /** Id подборок из [bookmarkFolders], в которые уже добавлен текущий тайтл. */
    val folderMemberships: Set<Int> = emptySet(),
    val error: String? = null,
)

sealed interface DetailsEvent {
    data object ToggleDownload : DetailsEvent

    /**
     * Переключить «Буду смотреть»: нативный `watching/togglewatchlist`, без своей логики.
     * Текущее состояние — [DetailsState.isWantToWatch].
     */
    data object ToggleWantToWatch : DetailsEvent

    /**
     * Добавить тайтл в подборку, если его там ещё нет, иначе убрать — состояние читается из
     * [DetailsState.folderMemberships]. Один и тот же диалог одинаково работает и для «Буду
     * смотреть», и для любой пользовательской подборки.
     */
    data class ToggleFolder(val folder: BookmarkFolder) : DetailsEvent

    /** Создать новую подборку и сразу добавить в неё текущий тайтл. */
    data class CreateFolderAndAdd(val title: String) : DetailsEvent
}

sealed interface DetailsSideEffect
