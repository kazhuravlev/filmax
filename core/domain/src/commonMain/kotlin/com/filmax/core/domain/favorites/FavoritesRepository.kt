package com.filmax.core.domain.favorites

import com.filmax.core.domain.favorites.model.FavoriteItem
import kotlinx.coroutines.flow.Flow

interface FavoritesRepository {
    val favorites: Flow<List<FavoriteItem>>
    val favoriteIds: Flow<Set<Int>>

    fun isFavorite(id: Int): Flow<Boolean>

    suspend fun toggle(item: FavoriteItem): Boolean

    suspend fun add(item: FavoriteItem)

    suspend fun remove(id: Int)
}
