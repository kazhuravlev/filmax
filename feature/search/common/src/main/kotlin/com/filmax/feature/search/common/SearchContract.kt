package com.filmax.feature.search.common

import com.filmax.core.domain.catalog.CatalogFilters
import com.filmax.core.domain.catalog.CatalogSort
import com.filmax.core.domain.catalog.SortOption
import com.filmax.core.domain.catalog.model.Country
import com.filmax.core.domain.catalog.model.Genre
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemType

const val MIN_QUERY_LENGTH = 2

data class SearchState(
    val query: String = "",
    val filter: ItemType? = null,
    val sort: SortOption = SortOption(CatalogSort.VIEWS),
    val filters: CatalogFilters = CatalogFilters(),
    val genres: List<Genre> = emptyList(),
    val selectedGenreId: Int? = null,
    val countries: List<Country> = emptyList(),
    val results: List<Item> = emptyList(),
    val catalogItems: List<Item> = emptyList(),
    val catalogEnabled: Boolean = false,
    val catalogLoadingMore: Boolean = false,
    val catalogEndReached: Boolean = false,
    val recentQueries: List<String> = emptyList(),
    val trendingQueries: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val visibleItems: List<Item>
        get() = if (query.length >= MIN_QUERY_LENGTH) results else catalogItems
}

sealed interface SearchEvent {
    data class QueryChange(val query: String) : SearchEvent
    data class FilterChange(val filter: ItemType?) : SearchEvent

    data class SortChange(val sort: SortOption) : SearchEvent

    data class GenreChange(val genreId: Int?) : SearchEvent

    data class ApplyFilters(val filters: CatalogFilters) : SearchEvent

    data object ResetFilters : SearchEvent

    data class SubmitQuery(val query: String) : SearchEvent
    data object ClearRecent : SearchEvent

    data object LoadCatalog : SearchEvent

    data object Refresh : SearchEvent

    data object LoadMoreCatalog : SearchEvent
}

sealed interface SearchSideEffect
