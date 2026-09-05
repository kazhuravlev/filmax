package com.filmax.core.domain.favorites

import com.filmax.core.domain.favorites.model.FavoriteItem
import kotlinx.coroutines.flow.Flow

/**
 * «Буду смотреть» — на сервере, поверх выделенной папки-закладки kino.watch (`bookmarks/{id}`),
 * а не только локально: нативный `watching/togglewatchlist` отдаёт лишь тоггл и флаг
 * `inWatchlist`, но НЕ список, поэтому список тайтлов берём из закладок. Локальный кэш — зеркало
 * для мгновенного показа и офлайна; источник правды всё равно сервер (см. `FavoritesRepositoryImpl`).
 */
interface FavoritesRepository {
    val favorites: Flow<List<FavoriteItem>>
    val favoriteIds: Flow<Set<Int>>

    fun isFavorite(id: Int): Flow<Boolean>

    /** Переключает локальное состояние, возвращает новое (true — в избранном). */
    suspend fun toggle(item: FavoriteItem): Boolean

    suspend fun add(item: FavoriteItem)

    suspend fun remove(id: Int)
}
