package com.filmax.data.catalog

import com.filmax.core.domain.cache.ItemDetailsCache
import com.filmax.core.domain.catalog.CatalogFilters
import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.catalog.CatalogSort
import com.filmax.core.domain.catalog.SortOption
import com.filmax.core.domain.catalog.model.Collection
import com.filmax.core.domain.catalog.model.CollectionPage
import com.filmax.core.domain.catalog.model.Country
import com.filmax.core.domain.catalog.model.Genre
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemPage
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.common.safeRequest
import com.filmax.core.network.networkJson
import com.filmax.data.catalog.mapper.hasFullDetails
import com.filmax.data.catalog.mapper.itemCacheKey
import com.filmax.data.catalog.mapper.similarCacheKey
import com.filmax.data.catalog.mapper.toDomain
import com.filmax.data.catalog.mapper.toDomainOnly
import com.filmax.data.catalog.remote.CatalogApi
import com.filmax.data.catalog.remote.ItemsQuery
import com.filmax.data.catalog.remote.ItemsShortcut
import com.filmax.data.catalog.remote.dto.ItemDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val QUALITY_4K = 4

private val FORCE_REFRESH_ADOPTION_TTL = 45.seconds

@Suppress("TooManyFunctions")
internal class CatalogRepositoryImpl(
    private val api: CatalogApi,
    private val itemCache: ItemDetailsCache,
) : CatalogRepository {
    private val detailsFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightDetails = ConcurrentHashMap<Int, Deferred<RequestResult<Item>>>()

    private val recentForceRefresh = ConcurrentHashMap<Int, RecentForceRefresh>()

    private data class RecentForceRefresh(
        val result: RequestResult.Success<Item>,
        val fetchedAt: TimeMark,
    )

    override suspend fun getItems(type: ItemType, sort: CatalogSort, page: Int): RequestResult<ItemPage> =
        safeRequest { api.getItems(type.apiValue, sort.descending, page).toDomain() }

    override suspend fun getItemsByGenre(
        type: ItemType,
        genreId: Int,
        sort: CatalogSort,
        page: Int,
    ): RequestResult<ItemPage> =
        safeRequest { api.getItemsByGenre(type.apiValue, genreId, sort.descending, page).toDomain() }

    override suspend fun getItems(
        type: ItemType,
        genreId: Int?,
        filters: CatalogFilters,
        sort: SortOption,
        page: Int,
    ): RequestResult<ItemPage> =
        safeRequest { api.getFilteredItems(filters.toQuery(type, genreId, sort, page)).toDomain() }

    override suspend fun getHotItems(type: ItemType, page: Int): RequestResult<ItemPage> =
        safeRequest { api.getItemsByShortcut(ItemsShortcut.Hot, type.apiValue, page).toDomain() }

    override suspend fun getNewItems(type: ItemType, page: Int): RequestResult<ItemPage> =
        safeRequest { api.getItemsByShortcut(ItemsShortcut.New, type.apiValue, page).toDomain() }

    override suspend fun getItemDetails(
        id: Int,
        forceRefresh: Boolean,
        isBackground: Boolean,
    ): RequestResult<Item> {
        val adopted = if (forceRefresh) {
            recentForceRefresh.remove(id)
                ?.takeIf { it.fetchedAt.elapsedNow() < FORCE_REFRESH_ADOPTION_TTL }
                ?.result
        } else {
            null
        }
        val cached = if (forceRefresh || adopted != null) null else readCachedItemDto(id)
        return when {
            adopted != null -> adopted
            cached?.hasFullDetails == true -> RequestResult.Success(cached.toDomainOnly())
            else -> inFlightDetails.computeIfAbsent(id) {
                detailsFetchScope.async {
                    safeRequest { api.getItemDetails(id, isBackground).item.toDomain() }.also { result ->
                        if (forceRefresh && result is RequestResult.Success) {
                            recentForceRefresh[id] = RecentForceRefresh(result, TimeSource.Monotonic.markNow())
                        }
                    }
                }
                    .also { job -> job.invokeOnCompletion { inFlightDetails.remove(id, job) } }
            }.await()
        }
    }

    override suspend fun getCachedItemDetails(id: Int): Item? = readCachedItemDto(id)?.toDomainOnly()

    private suspend fun readCachedItemDto(id: Int): ItemDto? = itemCache.get(itemCacheKey(id))?.let { json ->
        runCatching { networkJson.decodeFromString<ItemDto>(json) }.getOrNull()
    }

    override suspend fun invalidateItemCache(id: Int) {
        itemCache.remove(itemCacheKey(id))
    }

    override suspend fun getSimilarItems(id: Int): RequestResult<List<Item>> = safeRequest {
        val cacheKey = similarCacheKey(id)
        val cached = itemCache.get(cacheKey)
        val items = if (cached != null) {
            networkJson.decodeFromString<List<ItemDto>>(cached).map { it.toDomainOnly() }
        } else {
            val dtos = api.getSimilarItems(id).items
            itemCache.remember(cacheKey, networkJson.encodeToString(dtos))
            dtos.map { it.toDomain() }
        }
        items.distinctBy { it.id }
    }

    override suspend fun getGenres(): RequestResult<List<Genre>> =
        safeRequest { api.getGenres().items.map { it.toDomain() } }

    override suspend fun getCountries(): RequestResult<List<Country>> =
        safeRequest { api.getCountries().items.map { it.toDomain() } }

    override suspend fun getCollections(page: Int): RequestResult<List<Collection>> =
        safeRequest { api.getCollections(page = page).items.map { it.toDomain() }.distinctBy { it.id } }

    override suspend fun getCollectionItems(collectionId: Int, page: Int): RequestResult<CollectionPage> =
        safeRequest {
            val collectionPage = api.getCollectionItems(collectionId, page).toDomain()
            collectionPage.copy(items = collectionPage.items.distinctBy { it.id })
        }
}

private val CatalogSort.descending: String get() = "-$apiValue"

private fun CatalogFilters.toQuery(
    type: ItemType,
    genreId: Int?,
    sort: SortOption,
    page: Int,
): ItemsQuery = ItemsQuery(
    type = type.apiValue,
    sort = sort.apiValue,
    page = page,
    genreId = genreId,
    countryId = countryId,
    quality = if (only4k) QUALITY_4K else null,
    finished = onlyFinished?.let { if (it) 1 else 0 },
    conditions = buildConditions(),
)

private fun CatalogFilters.buildConditions(): List<String> = buildList {
    yearFrom?.let { add("year>=$it") }
    yearTo?.let { add("year<=$it") }
    kpRatingFrom?.let { add("kinopoisk_rating>=$it") }
    imdbRatingFrom?.let { add("imdb_rating>=$it") }
}
