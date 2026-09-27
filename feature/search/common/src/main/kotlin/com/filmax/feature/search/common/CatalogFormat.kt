
package com.filmax.feature.search.common

import com.filmax.core.domain.catalog.CatalogSort
import com.filmax.core.domain.catalog.model.ItemType

val TypeOptions = listOf(
    null to "Все",
    ItemType.MOVIE to "Фильмы",
    ItemType.SERIES to "Сериалы",
    ItemType.ANIME to "Аниме",
    ItemType.DOCUMENTARY to "Документальные",
)

val SortOptions = listOf(
    CatalogSort.UPDATED to "Обновлённые",
    CatalogSort.CREATED to "Добавленные",
    CatalogSort.RATING to "Рейтинг Filmax",
    CatalogSort.VIEWS to "Просмотры",
    CatalogSort.YEAR to "Год",
    CatalogSort.KINOPOISK_RATING to "Рейтинг КП",
    CatalogSort.IMDB_RATING to "Рейтинг IMDb",
)

fun itemTypeLabel(type: ItemType): String = when (type) {
    ItemType.MOVIE -> "Фильм"
    ItemType.SERIES -> "Сериал"
    ItemType.ANIME -> "Аниме"
    ItemType.DOCUMENTARY -> "Документальный"
    ItemType.TV -> "ТВ"
}

fun sortLabel(sort: CatalogSort): String =
    SortOptions.firstOrNull { it.first == sort }?.second ?: SortOptions.first().second
