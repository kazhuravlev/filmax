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
 * Для сериала эта подборка — не замена нативному watchlist, а дубль: экран деталей пишет в обе
 * (см. `DetailsScreenModel.toggleFolder`), а страница «Я смотрю» комбинирует и дедуплицирует.
 */
interface WatchingNowRepository {
    /** Реактивно: тайтл сейчас в подборке — для мгновенного (оптимистичного) значка на экране деталей. */
    fun isMember(id: Int): Flow<Boolean>

    /** Переключает и возвращает новое состояние (true — теперь в подборке). */
    suspend fun toggle(item: Item): Boolean

    /** Все тайтлы в подборке — источник для страницы «Я смотрю» у фильмов без прогресса. */
    suspend fun getAll(): RequestResult<List<Item>>
}
