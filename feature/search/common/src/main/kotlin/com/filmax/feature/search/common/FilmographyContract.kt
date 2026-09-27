package com.filmax.feature.search.common

import com.filmax.core.domain.catalog.model.Item

data class FilmographyState(
    val loading: Boolean = true,
    val heading: String = "",
    val items: List<Item> = emptyList(),
    val error: String? = null,
)

sealed interface FilmographyEvent {
    data object Retry : FilmographyEvent
}

sealed interface FilmographySideEffect
