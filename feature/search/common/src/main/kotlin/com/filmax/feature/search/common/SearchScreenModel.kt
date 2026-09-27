@file:Suppress("TooManyFunctions")

package com.filmax.feature.search.common

import com.filmax.core.domain.catalog.CatalogFilters
import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.catalog.CatalogSort
import com.filmax.core.domain.catalog.SortOption
import com.filmax.core.domain.catalog.model.Country
import com.filmax.core.domain.catalog.model.Genre
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.domain.common.LastValueCache
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.common.errorOrNull
import com.filmax.core.domain.common.getOrNull
import com.filmax.core.domain.error.AppError
import com.filmax.core.domain.search.SearchRepository
import com.filmax.core.presentation.BaseScreenModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

private const val SEARCH_DEBOUNCE_MILLIS = 400L
private const val PER_PAGE = 20
private const val RECENT_LIMIT = 8

private const val CATALOG_MAX_ITEMS = 500

private val BrowseTypes = listOf(ItemType.MOVIE, ItemType.SERIES, ItemType.DOCUMENTARY)

private const val ANIME_GENRE_ID = 25
private val AnimeTypes = listOf(ItemType.MOVIE, ItemType.SERIES)

private data class CatalogRequest(
    val filter: ItemType?,
    val selectedGenreId: Int?,
    val filters: CatalogFilters,
    val sort: SortOption,
) {
    val activeTypes: List<ItemType>
        get() = when (filter) {
            null -> BrowseTypes
            ItemType.ANIME -> AnimeTypes
            ItemType.MOVIE, ItemType.SERIES, ItemType.DOCUMENTARY, ItemType.TV -> listOf(filter)
        }

    val apiGenreId: Int?
        get() = if (filter == ItemType.ANIME) ANIME_GENRE_ID else selectedGenreId

    fun narrow(items: List<Item>): List<Item> =
        if (filter == ItemType.ANIME && selectedGenreId != null) {
            items.filter { item -> item.genres.any { it.id == selectedGenreId } }
        } else {
            items
        }

    fun isDefaultCatalogRequest(): Boolean = this == DEFAULT_CATALOG_REQUEST

    companion object {
        private val DEFAULT_CATALOG_REQUEST = CatalogRequest(
            filter = null,
            selectedGenreId = null,
            filters = CatalogFilters(),
            sort = SortOption(CatalogSort.VIEWS),
        )
    }
}

private data class CatalogPage(
    val items: List<Item>,
    val exhaustedTypes: Set<ItemType>,
    val hadErrors: Boolean = false,
)

private val VIDEO_GENRE_TYPES = setOf("movie", "serial", "anime", "docuserial", "documovie", "tvshow", "3d")

private val TrendingQueries = listOf(
    "Мстители",
    "Дюна",
    "Офис",
    "Ведьмак",
    "Интерстеллар",
    "Во все тяжкие",
    "Оппенгеймер",
    "Игра престолов",
)

class SearchScreenModel(
    private val search: SearchRepository,
    private val catalog: CatalogRepository,
    private val catalogSnapshotCache: LastValueCache<CatalogSnapshot>,
) : BaseScreenModel<SearchState, SearchSideEffect, SearchEvent>(SearchState()) {
    private val queryFlow = MutableStateFlow("")

    private var catalogPage = 0

    private var exhaustedTypes = setOf<ItemType>()

    private val retryVisibleContent: () -> Unit = {
        screenModelScope { _ -> reload() }
        if (state.catalogEnabled) loadCatalogMetadata()
    }

    init {
        onFetchData()
    }

    override fun dispatch(event: SearchEvent) {
        when (event) {
            is SearchEvent.QueryChange -> onQueryChange(event.query)
            is SearchEvent.FilterChange -> updateAndReload { it.copy(filter = event.filter) }
            is SearchEvent.SortChange -> updateAndReload { it.copy(sort = event.sort) }
            is SearchEvent.GenreChange -> updateAndReload { it.copy(selectedGenreId = event.genreId) }
            is SearchEvent.ApplyFilters -> updateAndReload { it.copy(filters = event.filters) }
            SearchEvent.ResetFilters -> updateAndReload { it.copy(filters = CatalogFilters()) }
            is SearchEvent.SubmitQuery -> {
                onQueryChange(event.query)
                screenModelScope { _ -> performSearch(event.query) }
            }

            SearchEvent.ClearRecent -> screenModelScope { _ ->
                updateState { it.copy(recentQueries = emptyList()) }
            }

            SearchEvent.LoadCatalog -> onLoadCatalog()
            SearchEvent.Refresh -> retryVisibleContent()
            SearchEvent.LoadMoreCatalog -> onLoadMoreCatalog()
        }
    }

    private fun onLoadCatalog() {
        if (state.catalogEnabled) return
        screenModelScope { _ ->
            val seed = if (state.catalogItems.isEmpty() && state.genres.isEmpty()) {
                catalogSnapshotCache.get()
            } else {
                null
            }
            updateState {
                it.copy(
                    catalogEnabled = true,
                    catalogItems = seed?.items ?: it.catalogItems,
                    genres = seed?.genres ?: it.genres,
                    countries = seed?.countries ?: it.countries,
                )
            }
            reload()
        }
        loadCatalogMetadata()
    }

    @OptIn(FlowPreview::class)
    override fun onFetchData() {
        screenModelScope { _ -> updateState { it.copy(trendingQueries = TrendingQueries) } }
        screenModelScope { _ ->
            queryFlow
                .drop(1)
                .debounce(SEARCH_DEBOUNCE_MILLIS)
                .distinctUntilChanged()
                .collectLatest { reload() }
        }
    }

    private fun onQueryChange(query: String) {
        queryFlow.value = query
        val searching = query.length >= MIN_QUERY_LENGTH
        screenModelScope { _ ->
            updateState {
                it.copy(
                    query = query,
                    error = null,
                    loading = searching,
                    results = if (searching) it.results else emptyList(),
                )
            }
        }
    }

    private fun updateAndReload(change: (SearchState) -> SearchState) {
        screenModelScope { _ ->
            updateState(change)
            reload()
        }
    }

    private fun loadCatalogMetadata() {
        screenModelScope { _ ->
            when (val result = catalog.getGenres()) {
                is RequestResult.Success -> {
                    updateState {
                        it.copy(genres = result.data.filter { genre -> genre.type in VIDEO_GENRE_TYPES })
                    }
                    if (state.catalogRequest().isDefaultCatalogRequest()) {
                        catalogSnapshotCache.put(state.asCatalogSnapshot())
                    }
                }

                is RequestResult.Error -> showServerRetryNotice()
            }
        }
        screenModelScope { _ ->
            when (val result = catalog.getCountries()) {
                is RequestResult.Success -> {
                    updateState { it.copy(countries = result.data) }
                    if (state.catalogRequest().isDefaultCatalogRequest()) {
                        catalogSnapshotCache.put(state.asCatalogSnapshot())
                    }
                }

                is RequestResult.Error -> showServerRetryNotice()
            }
        }
    }

    private suspend fun reload() {
        val query = state.query
        if (query.length >= MIN_QUERY_LENGTH) performSearch(query) else loadCatalog()
    }

    private suspend fun performSearch(query: String) {
        updateState { it.copy(loading = true) }
        val result = search.search(query, state.filter, perPage = PER_PAGE)
        when (result) {
            is RequestResult.Success -> updateState { current ->
                val recent = (listOf(query) + current.recentQueries).distinct().take(RECENT_LIMIT)
                current.copy(
                    loading = false,
                    results = arrange(result.data, current),
                    recentQueries = recent,
                    error = null,
                )
            }

            is RequestResult.Error -> updateState {
                it.copy(loading = false, error = result.message)
            }
        }
        if (result is RequestResult.Error) showServerRetryNotice()
    }

    private suspend fun loadCatalog() {
        if (!state.catalogEnabled) return
        val request = state.catalogRequest()
        updateState { it.copy(loading = true, catalogLoadingMore = false, catalogEndReached = false) }
        when (val first = fetchCatalogPage(request, page = 1, exhausted = emptySet())) {
            is RequestResult.Success -> {
                if (state.matches(request)) {
                    catalogPage = 1
                    exhaustedTypes = first.data.exhaustedTypes
                    updateState {
                        it.copy(
                            loading = false,
                            catalogItems = sortLocally(first.data.items.distinctById(), request.sort),
                            catalogEndReached = request.activeTypes.all { it in first.data.exhaustedTypes },
                            error = null,
                        )
                    }
                    request.takeIf { it.isDefaultCatalogRequest() }
                        ?.let { catalogSnapshotCache.put(state.asCatalogSnapshot()) }
                    first.data.takeIf(CatalogPage::hadErrors)?.let {
                        showServerRetryNotice()
                    }
                }
            }

            is RequestResult.Error -> {
                if (state.matches(request)) {
                    updateState {
                        it.copy(loading = false, error = first.message)
                    }
                    showServerRetryNotice()
                }
            }
        }
    }

    private fun onLoadMoreCatalog() {
        val current = state
        val busy = current.loading || current.catalogLoadingMore || current.catalogEndReached
        if (!current.catalogEnabled || busy || current.query.length >= MIN_QUERY_LENGTH) return
        val request = current.catalogRequest()
        val page = catalogPage + 1
        val exhausted = exhaustedTypes
        screenModelScope { _ ->
            updateState { it.copy(catalogLoadingMore = true) }
            when (val next = fetchCatalogPage(request, page, exhausted)) {
                is RequestResult.Success -> {
                    if (!state.matches(request)) return@screenModelScope
                    if (!next.data.hadErrors) catalogPage = page
                    exhaustedTypes = next.data.exhaustedTypes
                    updateState { s ->
                        val seen = s.catalogItems.mapTo(HashSet()) { it.id }
                        val merged = (s.catalogItems + next.data.items.filter { it.id !in seen })
                            .distinctById()
                            .take(CATALOG_MAX_ITEMS)
                        s.copy(
                            catalogLoadingMore = false,
                            catalogItems = sortLocally(merged, request.sort),
                            catalogEndReached = merged.size >= CATALOG_MAX_ITEMS ||
                                request.activeTypes.all { it in next.data.exhaustedTypes },
                        )
                    }
                    if (next.data.hadErrors) showServerRetryNotice()
                }

                is RequestResult.Error -> if (state.matches(request)) {
                    updateState { it.copy(catalogLoadingMore = false) }
                    showServerRetryNotice()
                }
            }
        }
    }

    private suspend fun fetchCatalogPage(
        request: CatalogRequest,
        page: Int,
        exhausted: Set<ItemType>,
    ): RequestResult<CatalogPage> {
        val types = request.activeTypes.filterNot { it in exhausted }
        if (types.isEmpty()) return RequestResult.Success(CatalogPage(emptyList(), exhausted))
        val results = coroutineScope {
            types.map { type ->
                async { type to catalog.getItems(type, request.apiGenreId, request.filters, request.sort, page) }
            }.awaitAll()
        }
        val succeeded = results.mapNotNull { (type, result) ->
            result.getOrNull()?.let { itemPage -> type to itemPage }
        }
        return if (succeeded.isEmpty()) {
            results.firstNotNullOfOrNull { (_, result) -> result.errorOrNull() }
                ?: RequestResult.Error(AppError.Empty)
        } else {
            val nextExhausted = exhausted + succeeded
                .filter { (_, itemPage) -> itemPage.items.isEmpty() || !itemPage.pagination.hasNextPage }
                .map { (type, _) -> type }
            RequestResult.Success(
                CatalogPage(
                    items = request.narrow(interleave(succeeded.map { (_, itemPage) -> itemPage.items })),
                    exhaustedTypes = nextExhausted,
                    hadErrors = results.any { (_, result) -> result is RequestResult.Error },
                ),
            )
        }
    }
}

private fun SearchState.catalogRequest(): CatalogRequest = CatalogRequest(
    filter = filter,
    selectedGenreId = selectedGenreId,
    filters = filters,
    sort = sort,
)

private fun SearchState.matches(request: CatalogRequest): Boolean = catalogRequest() == request

private fun arrange(items: List<Item>, state: SearchState): List<Item> {
    val genreId = state.selectedGenreId
    val filtered = items.asSequence()
        .filter { item -> genreId == null || item.genres.any { it.id == genreId } }
        .filter { item -> item.matches(state.filters) }
        .distinctBy { it.id }
        .toList()
    return sortLocally(filtered, state.sort)
}

private fun List<Item>.distinctById(): List<Item> = distinctBy { it.id }

private fun Item.matches(filters: CatalogFilters): Boolean {
    val yearFrom = filters.yearFrom
    val yearTo = filters.yearTo
    val kpFrom = filters.kpRatingFrom
    val imdbFrom = filters.imdbRatingFrom
    val finishedFilter = filters.onlyFinished
    return (yearFrom == null || year >= yearFrom) &&
        (yearTo == null || year <= yearTo) &&
        (kpFrom == null || ratingAtLeast(rating.kinopoisk, kpFrom)) &&
        (imdbFrom == null || ratingAtLeast(rating.imdb, imdbFrom)) &&
        (finishedFilter == null || finished == finishedFilter)
}

private fun ratingAtLeast(raw: String?, threshold: Int): Boolean {
    val value = raw?.toDoubleOrNull() ?: return false
    return value >= threshold
}

private fun sortLocally(items: List<Item>, sort: SortOption): List<Item> {
    val comparator = when (sort.field) {
        CatalogSort.RATING -> compareBy<Item> { it.rating.external }
        CatalogSort.YEAR -> compareBy<Item> { it.year }
        CatalogSort.UPDATED, CatalogSort.CREATED, CatalogSort.VIEWS, CatalogSort.KINOPOISK_RATING,
        CatalogSort.IMDB_RATING,
        -> return items
    }
    return if (sort.ascending) items.sortedWith(comparator) else items.sortedWith(comparator.reversed())
}

private fun interleave(lists: List<List<Item>>): List<Item> {
    if (lists.size == 1) return lists.first()
    val depth = lists.maxOf { it.size }
    return (0 until depth).flatMap { index -> lists.mapNotNull { it.getOrNull(index) } }
}

data class CatalogSnapshot(
    val items: List<Item> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val countries: List<Country> = emptyList(),
)

private fun SearchState.asCatalogSnapshot(): CatalogSnapshot =
    CatalogSnapshot(items = catalogItems, genres = genres, countries = countries)

private suspend fun fetchDefaultCatalogItems(catalog: CatalogRepository): List<Item> = coroutineScope {
    val pages = BrowseTypes.map { type ->
        async { catalog.getItems(type, null, CatalogFilters(), SortOption(CatalogSort.VIEWS)) }
    }.awaitAll().mapNotNull { it.getOrNull() }
    interleave(pages.map { it.items })
}

suspend fun fetchCatalogSnapshot(catalog: CatalogRepository): CatalogSnapshot = coroutineScope {
    val itemsDeferred = async { fetchDefaultCatalogItems(catalog) }
    val genresDeferred = async {
        catalog.getGenres().getOrNull()?.filter { genre -> genre.type in VIDEO_GENRE_TYPES }.orEmpty()
    }
    val countriesDeferred = async { catalog.getCountries().getOrNull().orEmpty() }
    CatalogSnapshot(
        items = itemsDeferred.await(),
        genres = genresDeferred.await(),
        countries = countriesDeferred.await(),
    )
}
