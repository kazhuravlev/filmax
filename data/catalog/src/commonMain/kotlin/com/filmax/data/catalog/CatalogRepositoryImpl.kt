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
import com.filmax.data.catalog.mapper.itemCacheKey
import com.filmax.data.catalog.mapper.similarCacheKey
import com.filmax.data.catalog.mapper.toDomain
import com.filmax.data.catalog.mapper.toDomainOnly
import com.filmax.data.catalog.remote.CatalogApi
import com.filmax.data.catalog.remote.ItemsQuery
import com.filmax.data.catalog.remote.dto.ItemDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

// Значение параметра `quality` для фильтра «только 4K» (kino.watch: 4 = 2160p).
private const val QUALITY_4K = 4

// Реализация всего контракта CatalogRepository — столько же методов, дробить незачем.
@Suppress("TooManyFunctions")
internal class CatalogRepositoryImpl(
    private val api: CatalogApi,
    private val itemCache: ItemDetailsCache,
) : CatalogRepository {

    // Свой скоуп, не завязанный на вызывающего: фоновая очередь (TitleBackgroundFetcherImpl) и
    // экран деталей, открытый пользователем ровно в этот момент, могут запросить один и тот же id
    // одновременно — оба должны дождаться ОДНОГО сетевого ответа, а не бить по сети и по
    // ItemDetailsCache дважды параллельно. Скоуп вызывающего для Deferred не годится: он живёт
    // только на время одного из двух вызовов и не должен обрывать результат для другого.
    private val detailsFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightDetails = ConcurrentHashMap<Int, Deferred<RequestResult<Item>>>()

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
        safeRequest { api.getItemsByShortcut("hot", type.apiValue, page).toDomain() }

    override suspend fun getNewItems(type: ItemType, page: Int): RequestResult<ItemPage> =
        safeRequest { api.getItemsByShortcut("new", type.apiValue, page).toDomain() }

    // Статическая информация о тайтле (название/описание/актёры/режиссёр/трейлер/жанры/рейтинги
    // и т.п.) почти не меняется — при попадании в кэш ItemDto.toDomain() уже сохранил его туда
    // (см. CatalogMapper), здесь только читаем: свежая запись — не ходим в сеть вовсе.
    //
    // forceRefresh игнорирует кэш-чтение: списочные эндпоинты кэшируют тайтл БЕЗ videos/seasons
    // (см. doc CatalogRepository.getItemDetails), поэтому перед воспроизведением нужен гарантированно
    // свежий ответ с реальными ссылками. toDomain() тут же перезаписывает кэш полными данными —
    // самолечение: следующий кэш-хит (в т.ч. для Details) уже увидит настоящий треклист.
    //
    // Сетевой поход (единственная небезопасная для гонки часть — дубль запроса и запись в кэш из
    // двух мест разом) схлопнут по id в [inFlightDetails]: пока он не забрал сюда ветку кэш-хита,
    // forceRefresh не важен — общий свежий ответ одинаково годится и тому, кто его форсировал, и
    // тому, кто просто не нашёл кэш. Так фоновый TitleBackgroundFetcherImpl и экран деталей,
    // открытый в тот же момент на тот же id, ждут ОДИН запрос, а не гоняют по два параллельно.
    override suspend fun getItemDetails(id: Int, forceRefresh: Boolean): RequestResult<Item> {
        val cached = if (forceRefresh) null else itemCache.get(itemCacheKey(id))
        if (cached != null) {
            return safeRequest { networkJson.decodeFromString<ItemDto>(cached).toDomainOnly() }
        }
        val deferred = inFlightDetails.computeIfAbsent(id) {
            detailsFetchScope.async { safeRequest { api.getItemDetails(id).item.toDomain() } }
                .also { job -> job.invokeOnCompletion { inFlightDetails.remove(id, job) } }
        }
        return deferred.await()
    }

    override suspend fun invalidateItemCache(id: Int) {
        itemCache.remove(itemCacheKey(id))
    }

    // distinctBy(id) здесь и в подборках: сервер может отдать тайтл дважды, а списки уходят
    // в Lazy-контейнеры с key = id — дубликат ключа роняет Compose («Key … was already used»).
    //
    // Список «похожих» тоже кэшируем (не только отдельные тайтлы в нём, это делает toDomain()
    // сам по себе): иначе открытие каждой карточки заново ждёт этот запрос, хотя список для
    // конкретного тайтла почти не меняется. Кэш-хит — toDomainOnly (без повторной заявки в
    // фоновую закачку картинок/переучёта TTL, см. CatalogMapper), промах — обычный toDomain().
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

/**
 * Короткие перегрузки без [SortOption] всегда сортируют по убыванию: «популярное», «лучшее»
 * и «свежее» читаются сверху вниз. kino.watch: минус-префикс = DESC (см. [SortOption.apiValue]).
 */
private val CatalogSort.descending: String get() = "-$apiValue"

/**
 * Разворачивает доменные [CatalogFilters] в параметры `api/v1/items`. Диапазоны года и пороги
 * рейтингов уходят повторяемыми `conditions[]`, страна/качество/завершённость — отдельными
 * параметрами (так их принимает kino.watch).
 */
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
    // finished=1 — только завершённые, finished=0 — только продолжающиеся, отсутствие — любые.
    finished = onlyFinished?.let { if (it) 1 else 0 },
    conditions = buildConditions(),
)

private fun CatalogFilters.buildConditions(): List<String> = buildList {
    yearFrom?.let { add("year>=$it") }
    yearTo?.let { add("year<=$it") }
    kpRatingFrom?.let { add("kinopoisk_rating>=$it") }
    imdbRatingFrom?.let { add("imdb_rating>=$it") }
}
