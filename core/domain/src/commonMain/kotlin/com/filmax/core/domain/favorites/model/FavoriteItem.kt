package com.filmax.core.domain.favorites.model

import com.filmax.core.domain.catalog.model.Item

data class FavoriteItem(
    val id: Int,
    val title: String,
    val posterSmall: String,
    val year: Int,
    val durationMinutes: Int,
)

fun Item.toFavoriteItem() = FavoriteItem(
    id = id,
    title = title,
    posterSmall = posters.medium.ifBlank { posters.small },
    year = year,
    durationMinutes = duration.averageMinutes?.toInt() ?: 0,
)
