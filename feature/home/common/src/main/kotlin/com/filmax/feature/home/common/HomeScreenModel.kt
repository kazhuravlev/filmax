package com.filmax.feature.home.common

import com.filmax.core.domain.cache.ImageCacheKeys
import com.filmax.core.domain.cache.ImageDiscovery
import com.filmax.core.domain.cache.PrefetchImage
import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.catalog.CatalogSort
import com.filmax.core.domain.catalog.model.Collection
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.domain.common.LastValueCache
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.common.getOrNull
import com.filmax.core.domain.user.UserRepository
import com.filmax.core.domain.user.model.initials
import com.filmax.core.domain.watching.WatchingRepository
import com.filmax.core.domain.watching.model.Continuation
import com.filmax.core.domain.watching.model.ContinuationResolver
import com.filmax.core.presentation.BaseScreenModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// Экран главной параллелит все свои источники (hero, продолжение просмотра, ряды каталога,
// подборки) и красит каждый по готовности — один короткий обработчик на источник, дробить их по
// классам ради лимита незачем.
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
            HomeEvent.Load -> {
                resetServerRetryCycle()
                onFetchData()
            }
            is HomeEvent.LoadMoreRow -> loadMoreRow(event.id)
        }
    }

    /**
     * Все источники стартуют одним `async` каждый и параллельно; каждый красит СВОЙ кусок
     * состояния сразу по готовности (не дожидаясь остальных) — экран поэтому и может показать
     * ряды по одному, а не всё разом. Итоговые `await()` в конце нужны только чтобы:
     *  а) знать, что относящийся к источнику `updateState` уже применился (иначе финальная
     *     проверка `state.isEmpty`/`allSucceeded` читала бы состояние до того, как оно обновилось —
     *     `updateState` внутри того же `async` гарантирует порядок);
     *  б) посчитать общую ошибку/оффлайн-баннер/кэш снапшота уже после того, как всё осело.
     */
    override fun onFetchData() {
        screenModelScope { _ ->
            // Первый вызов после (пере)создания модели — состояние ещё пустое, берём то, что было
            // при прошлом успешном проходе, из DI-кэша. Повторный вызов (HomeEvent.Load) — на
            // экране уже что-то есть, используем это как затравку: скелетоны покажут прежний
            // контент, пока грузится свежий, а не мигнут пустотой.
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
                val result = watching.getHistory()
                val entries = result.getOrNull()
                    ?.let { continuations.resolve(it) }
                    ?.filter { it.isActualContinuation }
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
            val error = allResults.firstNotNullOfOrNull { (it as? RequestResult.Error)?.message }
            val allSucceeded = allResults.all { it is RequestResult.Success<*> }
            if (allSucceeded) snapshotCache.put(state.asSnapshot())
            if (error != null) scheduleServerRetry(::onFetchData)
            when {
                // Пусто + ошибка — блокирующая модалка.
                state.isEmpty && error != null -> showError(error)
                // Что-то не обновилось свежим (сеть/сбой) — контент из кэша/затравки, баннер «нет сети».
                !allSucceeded -> showOfflineBanner()
                else -> {
                    dismissOfflineBanner()
                    dismissError()
                }
            }
        }
    }

    /** Один ряд каталога: 1 тип почти всегда, 2 (movie+serial) только у «Аниме» — жанр общий
     * на оба (см. [HOME_CATALOG_ROWS]). Частичный успех (один тип ответил, другой упал) всё
     * равно считается успехом ряда — лучше неполный ряд, чем пустой из-за одного сбоя. */
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
        val error = perType.firstNotNullOfOrNull { (it as? RequestResult.Error)?.message }
        if (items.isEmpty() && error != null) RequestResult.Error(error) else RequestResult.Success(items)
    }

    /** Инициалы для аватара в шапке — best-effort, ошибки не мешают ленте. */
    private fun fetchUserInitials() {
        screenModelScope {
            (user.getProfile() as? RequestResult.Success)?.let { result ->
                updateState { it.copy(initials = result.data.initials()) }
            }
        }
    }

    private fun loadMoreRow(id: String) {
        when (val row = state.rows.firstOrNull { it.id == id }) {
            // История приходит из фида целиком — листать нечего.
            is HomeRow.Continue, null -> Unit
            is HomeRow.Titles -> loadMoreTitles(row)
            is HomeRow.Collections -> loadMoreCollections(row)
        }
    }

    /** Догрузка для 1 или 2 типов (см. [HomeRow.Titles.types]): страница у каждого типа своя,
     * но общий номер страницы один на ряд — источник, который уже исчерпался, просто продолжит
     * отдавать пустые страницы (безвредно для [RowPaging.append]), пока не исчерпаются оба. */
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
            val error = results.firstNotNullOfOrNull { (it as? RequestResult.Error)?.message }
            if (error != null) {
                updateTitlesRow(row.id) { it.copy(paging = it.paging.copy(loadingMore = false)) }
                scheduleServerRetry { loadMoreRow(row.id) }
            } else {
                val pages = results.mapNotNull { it.getOrNull() }
                val merged = pages.flatMap { it.items }
                val hasNextPage = pages.any { it.pagination.hasNextPage }
                updateTitlesRow(row.id) { it.copy(paging = it.paging.append(merged, Item::id, hasNextPage)) }
            }
        }
    }

    /**
     * Догрузка «Подборок» отдельно от [loadMoreTitles]: другой источник, и репозиторий отдаёт
     * список без пагинации — конец определяется пустой страницей.
     */
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
            if (result is RequestResult.Error) scheduleServerRetry { loadMoreRow(row.id) }
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

/** Один настраиваемый ряд каталога на главной — зеркалит `home_blocks` реального конфига сервера
 * kino.watch (kpapp.link/config.json, сортировка везде «новое»). Берём только то, что `ItemType`
 * уже умеет: Detail/Player экраны никогда не проверялись на «concert»/«documovie»/«tvshow»/«3d» —
 * раздувать домен ради непроверенного риска не стоит, эти строки конфига просто нет смысла
 * заводить. «Аниме» — 2 типа (movie+serial) с одним общим жанром 25, как и в конфиге сервера. */
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

/**
 * Последний успешно загруженный целиком снимок главной — офлайн-устойчивость (issue #42) в
 * новой, параллельной модели загрузки: вместо одного атомарного фида кэшируем то же самое, но
 * ключуя ряды каталога по id, а не именованными полями. Пишется только когда абсолютно все
 * источники прохода отработали успешно (см. [HomeScreenModel.onFetchData]); читается как затравка
 * для скелетонов на первом проходе после (пере)создания модели.
 */
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

/** Начальные ряды на входе в [HomeScreenModel.onFetchData]: `loading = true` для всех — экран
 * рисует скелетон, пока конкретный ряд ждёт свои данные; содержимое — из затравки, если есть
 * (см. [HomeSnapshot]), чтобы не мигать пустотой поверх уже показанного контента при рефетче. */
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

/** Заменяет содержимое на свежее, если оно пришло; иначе оставляет прежнее (затравку/предыдущий
 * успешный ответ) — так ошибка одного источника не стирает то, что уже было показано. */
private fun <T> RowPaging<T>.seededWith(fresh: List<T>?): RowPaging<T> =
    if (fresh != null) RowPaging(items = fresh) else this

/**
 * Потолок карточек в одном ряду: бесконечный ряд на пульте — сотни нажатий вправо, а каждая
 * сотня карточек ещё и держит в памяти постеры. Дальше пусть зовёт Каталог.
 */
private const val HOME_ROW_MAX = 100

/** Сколько последних тайтлов показать в блоке «Продолжить просмотр». */
private const val CONTINUE_WATCHING_LIMIT = 5

/** Сколько подборок показать в горизонтальном ряду. */
private const val COLLECTIONS_LIMIT = 5

/** Сколько тайтлов показать в горизонтальных рядах каталога на старте. */
private const val ROW_LIMIT = 10

/** Ряд можно листать дальше: не занят, не кончился, не упёрся в потолок и вообще не пуст. */
private val RowPaging<*>.canLoadMore: Boolean
    get() = !loadingMore && !endReached && items.isNotEmpty() && items.size < HOME_ROW_MAX

/**
 * Фон-бэкдроп прогреваем ТОЛЬКО для этих двух маленьких наборов, а не для каждого тайтла везде
 * (см. `CatalogMapper.posterPrefetchImages` — там теперь только маленький постер): именно здесь,
 * в hero и «Продолжить просмотр», бэкдроп реально показывается (см. `TvHero`/`TvContinueCard` в
 * `feature:home:tv`) — ключ и источник url должны буква в букву совпадать с тем, что рисует экран,
 * иначе прогрев зря скачал бы то, что экран потом всё равно попросит под другим ключом.
 */
private fun Item.heroBackdropPrefetch(): PrefetchImage? {
    val url = posters.wide ?: posters.big.takeIf { it.isNotBlank() } ?: return null
    val subId = if (posters.wide != null) ImageCacheKeys.WALL else ImageCacheKeys.SIZE_BIG
    return PrefetchImage(ImageCacheKeys.poster(type.apiValue, id, subId), url)
}

private fun Continuation.backdropPrefetch(): PrefetchImage? {
    val url = wideOrPoster.takeIf { it.isNotBlank() } ?: return null
    return PrefetchImage(ImageCacheKeys.poster(item.type.apiValue, itemId, ImageCacheKeys.WALL), url)
}

/**
 * Приклеивает страницу к ряду: дедуп по id (страницы kino.watch пересекаются) и потолок ряда.
 * Пустая страница, отсутствие следующей или упёршийся потолок означают конец.
 */
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
