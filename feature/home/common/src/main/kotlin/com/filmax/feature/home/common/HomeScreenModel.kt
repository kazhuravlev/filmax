package com.filmax.feature.home.common

import com.filmax.core.domain.cache.ImageCacheKeys
import com.filmax.core.domain.cache.ImageDiscovery
import com.filmax.core.domain.cache.PosterSize
import com.filmax.core.domain.cache.PrefetchImage
import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.catalog.CatalogSort
import com.filmax.core.domain.catalog.model.Collection
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.domain.common.LastValueCache
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.common.errorOrNull
import com.filmax.core.domain.common.getOrNull
import com.filmax.core.domain.user.UserRepository
import com.filmax.core.domain.user.model.initials
import com.filmax.core.domain.watching.WatchingRepository
import com.filmax.core.domain.watching.model.Continuation
import com.filmax.core.domain.watching.model.ContinuationResolver
import com.filmax.core.domain.watching.model.WatchingListType
import com.filmax.core.presentation.BaseScreenModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Suppress("TooManyFunctions")
class HomeScreenModel(
    private val catalog: CatalogRepository,
    private val watching: WatchingRepository,
    private val continuations: ContinuationResolver,
    private val snapshotCache: LastValueCache<HomeSnapshot>,
    private val user: UserRepository,
) : BaseScreenModel<HomeState, HomeSideEffect, HomeEvent>(HomeState()) {
    init {
        onFetchData()
        fetchUserInitials()
    }

    override fun dispatch(event: HomeEvent) {
        when (event) {
            HomeEvent.Load -> onFetchData()
            is HomeEvent.LoadMoreRow -> loadMoreRow(event.id)
        }
    }

    override fun onFetchData() {
        screenModelScope { _ ->
            val isRefresh = state.rows.isNotEmpty()
            val seed = if (state.hero == null && state.rows.isEmpty()) snapshotCache.get() else state.asSnapshot()
            updateState { it.copy(loading = false, heroLoading = true, hero = seed?.hero, rows = initialRows(seed)) }

            val heroResult = async {
                val result = catalog.getHotItems(ItemType.MOVIE)
                val fresh = result.getOrNull()?.items?.firstOrNull()
                updateState { s -> s.copy(heroLoading = false, hero = fresh ?: s.hero) }
                fresh?.let { ImageDiscovery.discovered(listOfNotNull(it.heroBackdropPrefetch())) }
                result
            }
            val continueResult = async {
                val historyDeferred = async { watching.getHistory(forceRefresh = isRefresh) }
                val inProgressDeferred = async { inProgressTitleIds() }
                val result = historyDeferred.await()
                val inProgressIds = inProgressDeferred.await()
                val entries = result.getOrNull()
                    ?.let { continuations.resolve(it) }
                    ?.filter { it.isActualContinuation }
                    ?.filter { inProgressIds == null || it.itemId in inProgressIds }
                    ?.take(CONTINUE_WATCHING_LIMIT)
                updateContinueRow { it.copy(loading = false, entries = entries ?: it.entries) }
                entries?.let { ImageDiscovery.discovered(it.mapNotNull(Continuation::backdropPrefetch)) }
                result
            }
            val collectionsResult = async {
                val result = catalog.getCollections()
                val fresh = result.getOrNull()?.take(COLLECTIONS_LIMIT)
                updateCollectionsRow { it.copy(loading = false, paging = it.paging.seededWith(fresh)) }
                result
            }
            val rowResults = HOME_CATALOG_ROWS.map { spec ->
                async {
                    val result = fetchRow(spec)
                    updateTitlesRow(spec.id) {
                        it.copy(loading = false, paging = it.paging.seededWith(result.getOrNull()))
                    }
                    result
                }
            }

            val allResults = listOf(heroResult.await(), continueResult.await(), collectionsResult.await()) +
                rowResults.map { it.await() }
            val error = allResults.firstNotNullOfOrNull { it.errorOrNull() }
            val allSucceeded = allResults.all { it is RequestResult.Success<*> }
            if (allSucceeded) snapshotCache.put(state.asSnapshot())
            if (error != null) showServerRetryNotice()
            when {
                state.isEmpty && error != null -> showError(error)
                !allSucceeded -> showOfflineBanner()
                else -> {
                    dismissOfflineBanner()
                    dismissError()
                }
            }
        }
    }

    private suspend fun inProgressTitleIds(): Set<Int>? = coroutineScope {
        val results = listOf(
            async { watching.getWatchingTitles(WatchingListType.Movies) },
            async { watching.getWatchingTitles(WatchingListType.Serials, subscribed = true) },
            async { watching.getWatchingTitles(WatchingListType.Serials, subscribed = false) },
        ).awaitAll()
        results.mapNotNull { it.getOrNull() }
            .takeIf { it.isNotEmpty() }
            ?.flatten()
            ?.mapTo(mutableSetOf()) { it.itemId }
    }

    private suspend fun fetchRow(spec: HomeCatalogRowSpec): RequestResult<List<Item>> = coroutineScope {
        val perType = spec.types.map { type ->
            async {
                if (spec.genreId != null) {
                    catalog.getItemsByGenre(type, spec.genreId, CatalogSort.CREATED, page = 1)
                } else {
                    catalog.getItems(type, CatalogSort.CREATED, page = 1)
                }
            }
        }.awaitAll()
        val items = perType.mapNotNull { it.getOrNull() }.flatMap { it.items }.distinctBy { it.id }.take(ROW_LIMIT)
        val error = perType.firstNotNullOfOrNull { it.errorOrNull() }
        if (items.isEmpty() && error != null) error else RequestResult.Success(items)
    }

    private fun fetchUserInitials() {
        screenModelScope {
            (user.getProfile() as? RequestResult.Success)?.let { result ->
                updateState { it.copy(initials = result.data.initials()) }
            }
        }
    }

    private fun loadMoreRow(id: String) {
        when (val row = state.rows.firstOrNull { it.id == id }) {
            is HomeRow.Continue, null -> Unit
            is HomeRow.Titles -> loadMoreTitles(row)
            is HomeRow.Collections -> loadMoreCollections(row)
        }
    }

    private fun loadMoreTitles(row: HomeRow.Titles) {
        if (!row.paging.canLoadMore) return
        val nextPage = row.paging.page + 1
        screenModelScope { _ ->
            updateTitlesRow(row.id) { it.copy(paging = it.paging.copy(loadingMore = true)) }
            val results = coroutineScope {
                row.types.map { type ->
                    async {
                        if (row.genreId != null) {
                            catalog.getItemsByGenre(type, row.genreId, CatalogSort.CREATED, nextPage)
                        } else {
                            catalog.getItems(type, CatalogSort.CREATED, nextPage)
                        }
                    }
                }.awaitAll()
            }
            val error = results.firstNotNullOfOrNull { it.errorOrNull() }
            if (error != null) {
                updateTitlesRow(row.id) { it.copy(paging = it.paging.copy(loadingMore = false)) }
                showServerRetryNotice()
            } else {
                val pages = results.mapNotNull { it.getOrNull() }
                val merged = pages.flatMap { it.items }
                val hasNextPage = pages.any { it.pagination.hasNextPage }
                updateTitlesRow(row.id) { it.copy(paging = it.paging.append(merged, Item::id, hasNextPage)) }
            }
        }
    }

    private fun loadMoreCollections(row: HomeRow.Collections) {
        if (!row.paging.canLoadMore) return
        val nextPage = row.paging.page + 1
        screenModelScope { _ ->
            updateCollectionsRow { it.copy(paging = it.paging.copy(loadingMore = true)) }
            val result = catalog.getCollections(nextPage)
            when (result) {
                is RequestResult.Success -> updateCollectionsRow {
                    val hasNextPage = result.data.isNotEmpty()
                    it.copy(paging = it.paging.append(result.data, Collection::id, hasNextPage))
                }

                is RequestResult.Error -> updateCollectionsRow { it.copy(paging = it.paging.copy(loadingMore = false)) }
            }
            if (result is RequestResult.Error) showServerRetryNotice()
        }
    }

    private suspend fun updateContinueRow(transform: (HomeRow.Continue) -> HomeRow.Continue) {
        updateRows { row -> if (row is HomeRow.Continue) transform(row) else row }
    }

    private suspend fun updateCollectionsRow(transform: (HomeRow.Collections) -> HomeRow.Collections) {
        updateRows { row -> if (row is HomeRow.Collections) transform(row) else row }
    }

    private suspend fun updateTitlesRow(id: String, transform: (HomeRow.Titles) -> HomeRow.Titles) {
        updateRows { row -> if (row is HomeRow.Titles && row.id == id) transform(row) else row }
    }

    private suspend fun updateRows(transform: (HomeRow) -> HomeRow) {
        updateState { it.copy(rows = it.rows.map(transform)) }
    }
}

private data class HomeCatalogRowSpec(
    val id: String,
    val title: String,
    val types: List<ItemType>,
    val genreId: Int? = null,
)

private val HOME_CATALOG_ROWS = listOf(
    HomeCatalogRowSpec("movie", "Фильмы", listOf(ItemType.MOVIE)),
    HomeCatalogRowSpec("serial", "Сериалы", listOf(ItemType.SERIES)),
    HomeCatalogRowSpec("cartoon", "Мультфильмы", listOf(ItemType.MOVIE), genreId = 23),
    HomeCatalogRowSpec("multserial", "Мультсериалы", listOf(ItemType.SERIES), genreId = 23),
    HomeCatalogRowSpec("anime", "Аниме", listOf(ItemType.MOVIE, ItemType.SERIES), genreId = 25),
    HomeCatalogRowSpec("docuserial", "Док. Сериалы", listOf(ItemType.DOCUMENTARY)),
    HomeCatalogRowSpec("standup", "Стендапы", listOf(ItemType.MOVIE), genreId = 101),
)

data class HomeSnapshot(
    val hero: Item? = null,
    val continueWatching: List<Continuation> = emptyList(),
    val collections: List<Collection> = emptyList(),
    val catalogRows: Map<String, List<Item>> = emptyMap(),
)

private fun HomeState.asSnapshot(): HomeSnapshot = HomeSnapshot(
    hero = hero,
    continueWatching = rows.filterIsInstance<HomeRow.Continue>().firstOrNull()?.entries.orEmpty(),
    collections = rows.filterIsInstance<HomeRow.Collections>().firstOrNull()?.paging?.items.orEmpty(),
    catalogRows = rows.filterIsInstance<HomeRow.Titles>().associate { it.id to it.paging.items },
)

private fun initialRows(seed: HomeSnapshot?): List<HomeRow> = buildList {
    add(HomeRow.Continue(entries = seed?.continueWatching.orEmpty(), loading = true))
    HOME_CATALOG_ROWS.forEach { spec ->
        add(
            HomeRow.Titles(
                id = spec.id,
                title = spec.title,
                types = spec.types,
                genreId = spec.genreId,
                loading = true,
                paging = RowPaging(items = seed?.catalogRows?.get(spec.id).orEmpty()),
            ),
        )
    }
    add(HomeRow.Collections(paging = RowPaging(items = seed?.collections.orEmpty()), loading = true))
}

private fun <T> RowPaging<T>.seededWith(fresh: List<T>?): RowPaging<T> =
    if (fresh != null) RowPaging(items = fresh) else this

private const val HOME_ROW_MAX = 100

private const val CONTINUE_WATCHING_LIMIT = 5

private const val COLLECTIONS_LIMIT = 5

private const val ROW_LIMIT = 10

private val RowPaging<*>.canLoadMore: Boolean
    get() = !loadingMore && !endReached && items.isNotEmpty() && items.size < HOME_ROW_MAX

internal fun Item.heroBackdropPrefetch(): PrefetchImage? {
    val url = posters.wide ?: posters.big.takeIf { it.isNotBlank() } ?: return null
    val size = if (posters.wide != null) PosterSize.Wall else PosterSize.Big
    return PrefetchImage(ImageCacheKeys.poster(type, id, size), url)
}

internal fun Continuation.backdropPrefetch(): PrefetchImage? {
    val url = wideOrPoster.takeIf { it.isNotBlank() } ?: return null
    return PrefetchImage(ImageCacheKeys.poster(item.type, itemId, PosterSize.Wall), url)
}

private fun <T> RowPaging<T>.append(page: List<T>, key: (T) -> Int, hasNextPage: Boolean): RowPaging<T> {
    val seen = items.mapTo(HashSet(), key)
    val merged = (items + page.filterNot { key(it) in seen }).take(HOME_ROW_MAX)
    return copy(
        items = merged,
        page = this.page + 1,
        loadingMore = false,
        endReached = page.isEmpty() || !hasNextPage || merged.size >= HOME_ROW_MAX,
    )
}
