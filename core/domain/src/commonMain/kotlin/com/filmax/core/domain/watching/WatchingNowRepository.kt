package com.filmax.core.domain.watching

import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.common.RequestResult
import kotlinx.coroutines.flow.Flow

/**
 * «Watching Now» — своя подборка-костыль для фильмов: у kino.watch нет ручного способа занести
 * фильм в «Я смотрю» (`watching/movies` наполняется только реальным прогрессом просмотра,
 * `togglewatchlist` работает лишь для сериалов — проверено запросами к боевому API). Поэтому
 * держим для этого обычную папку-закладку под этим названием: `getBookmarkFolders` → найти/
 * создать, `addToBookmark`/`removeFromBookmark` для переключения, `getBookmarkItems` для чтения.
 *
 * Кнопка «Буду смотреть» в hero экрана деталей ведёт ИСКЛЮЧИТЕЛЬНО эту подборку — для фильма и
 * для сериала одинаково, нативный watchlist и настоящую подборку «Буду смотреть»
 * ([com.filmax.core.domain.favorites.FavoritesRepository]) она не трогает (см.
 * `DetailsScreenModel.toggleWantToWatchFolder`): три независимых источника на одной кнопке раньше
 * расходились между собой и сериал было невозможно убрать из списка с этого экрана. Страница
 * «Я смотрю» комбинирует эту подборку с нативным прогрессом и дедуплицирует по id.
 */
interface WatchingNowRepository {
    /** Реактивно: тайтл сейчас в подборке — для мгновенного (оптимистичного) значка на экране деталей. */
    fun isMember(id: Int): Flow<Boolean>

    /** Переключает и возвращает новое состояние (true — теперь в подборке). */
    suspend fun toggle(item: Item): Boolean

    /** Все тайтлы в подборке — источник для страницы «Я смотрю» у фильмов без прогресса. */
    suspend fun getAll(): RequestResult<List<Item>>
}
