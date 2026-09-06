// Экран совмещает две роли (поиск + витрина каталога с фильтрами), отсюда и количество
// коротких обработчиков — дробить их по файлам ради лимита незачем (тот же компромисс,
// что у HomeScreenModel).
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
import com.filmax.core.domain.common.getOrNull
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

/**
 * Потолок витрины каталога. Раньше списка не было вовсе: каждая догрузка конкатенировала и
 * дедуплицировала ВЕСЬ накопленный список заново — стоимость росла вместе с ним без предела.
 * «Аниме» тянет сразу два типа на страницу (см. [AnimeTypes]) и набирает вдвое больше карточек
 * на то же число догрузок, поэтому первым же и упирался: за десятки нажатий «вниз» список
 * разгонялся до тысяч элементов, и это перемалывание на главном потоке (внутри `updateState`)
 * душило UI вплоть до ANR — воспринималось как вылет. Дальше пусть сужают жанром/фильтром,
 * а не листают тысячи карточек подряд.
 */
private const val CATALOG_MAX_ITEMS = 500

/**
 * Что показывает чип «Все». `api/v1/items` без параметра `type` не ходит, поэтому «все» —
 * это объединение конкретных типов; ItemType.TV (эфирные каналы) в витрину не входит.
 * ANIME здесь нет: у kino.watch нет такого типа (api/v1/types), и аниме-тайтлы уже входят
 * в выдачу как movie/serial со своим жанром.
 */
private val BrowseTypes = listOf(ItemType.MOVIE, ItemType.SERIES, ItemType.DOCUMENTARY)

/**
 * Чип «Аниме»: типа «anime» у kino.watch НЕТ — аниме это ЖАНР (id 25) поверх фильмов и
 * сериалов. Поэтому фильтр ANIME разворачивается в movie+serial с жанром [ANIME_GENRE_ID].
 * При дополнительном жанре пересечение завершается локально: параметр `genre` в API один.
 */
private const val ANIME_GENRE_ID = 25
private val AnimeTypes = listOf(ItemType.MOVIE, ItemType.SERIES)

/**
 * Снимок фильтров для одного запроса витрины. Он не даёт позднему ответу старого запроса
 * (например, «ужасы») перезаписать новый («сериалы + ужасы»).
 */
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
            else -> listOf(filter)
        }

    /** API принимает только один genre: для аниме это технический жанр аниме. */
    val apiGenreId: Int?
        get() = if (filter == ItemType.ANIME) ANIME_GENRE_ID else selectedGenreId

    /** API не умеет передать «аниме + ещё один жанр», поэтому пересечение завершаем локально. */
    fun narrow(items: List<Item>): List<Item> =
        if (filter == ItemType.ANIME && selectedGenreId != null) {
            items.filter { item -> item.genres.any { it.id == selectedGenreId } }
        } else {
            items
        }

    /** Витрина «Все» без единого фильтра/жанра при дефолтной сортировке — то, что открывает
     * первый вход в каталог и то, что кэширует [SearchScreenModel] для затравки (см.
     * [CatalogSnapshot]). Выборка с активными фильтрами не кэшируется — она сиюминутная. */
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

/**
 * Типы жанров, которые показываем в каталоге. `api/v1/genres` отдаёт одним списком жанры всех
 * разделов kino.watch, включая музыкальные («Blues», «Chillout»), — без этого фильтра они лезли
 * в чипы рядом с «Драмой». Значения совпадают с [ItemType.apiValue] соответствующих типов.
 */
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

    /** Последняя загруженная страница витрины (0 — ещё не грузили). Общая для всех типов. */
    private var catalogPage = 0

    /** Типы, у которых страницы кончились: их не запрашиваем при догрузке. */
    private var exhaustedTypes = setOf<ItemType>()

    /** Повторяет текущую выдачу и справочники. */
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

    /** Первое включение витрины каталога в этой сессии ScreenModel; повторные вызовы — no-op. */
    private fun onLoadCatalog() {
        if (state.catalogEnabled) return
        screenModelScope { _ ->
            // Единственная точка входа, откуда мы вообще читаем [catalogSnapshotCache]: если
            // фоновый прогрев AppWarmup уже успел положить туда дефолтную выдачу и жанры/страны,
            // красим ими сразу, вместо пустой сетки со спиннером на время первого сетевого
            // прохода. reload() ниже всё равно уходит в сеть и красит актуальный ответ поверх.
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
                // drop(1): StateFlow отдаёт текущее значение сразу при подписке, и без этого
                // стартовый пустой запрос вызвал бы загрузку витрины второй раз — следом за
                // той, что уже запустил LoadCatalog.
                .drop(1)
                .debounce(SEARCH_DEBOUNCE_MILLIS)
                .distinctUntilChanged()
                // collectLatest, а не launch на каждый запрос: на пульте текст набирают по
                // букве, и недосчитанный поиск прошлой подстроки не должен перезаписать
                // выдачу более свежего запроса.
                .collectLatest { reload() }
        }
    }

    /**
     * Реальный поиск срабатывает только после [SEARCH_DEBOUNCE_MILLIS] в [onFetchData] — здесь же
     * синхронно, на каждое нажатие клавиши, держим состояние в согласии с тем, что вот-вот
     * покажет [SearchState.visibleItems]. Без этого в окне ожидания дебаунса `results` оставались
     * пустыми/устаревшими от прошлого запроса при `loading == false`, и сетка на каждую букву
     * успевала мигнуть «Ничего не найдено».
     *
     * Пересекли порог [MIN_QUERY_LENGTH] снизу вверх — тут же включаем `loading`: снятие флага
     * дальше делает только завершившийся [performSearch] актуального запроса ([collectLatest] в
     * [onFetchData] сам отменяет отставший поиск, так что более старый ответ не собьёт флаг назад).
     * Опустились ниже порога — отменять сам поиск не нужно: он либо ещё не долетел до сети из-за
     * debounce, либо collectLatest оборвёт устаревший вызов сам; здесь мы просто мгновенно
     * возвращаем зрителя на витрину каталога, не дожидаясь этого запроса.
     */
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

    /** Смена любого фильтра — это всегда «поправь состояние и перезапроси выдачу». */
    private fun updateAndReload(change: (SearchState) -> SearchState) {
        screenModelScope { _ ->
            updateState(change)
            reload()
        }
    }

    /**
     * Метаданные грузятся параллельно сетке, но их ошибки больше не скрываются от пользователя.
     * Жанры и страны — общий для всех фильтров справочник (грузится ровно раз за жизнь модели,
     * см. охрану `!state.catalogEnabled` в [dispatch]), поэтому его самого безопасно класть в
     * [catalogSnapshotCache] сразу по готовности. А вот [SearchState.catalogItems] в этот момент
     * уже мог смениться на выдачу по фильтру (зритель успел щёлкнуть чип раньше, чем мы сюда
     * дошли) — на всякий случай кладём снимок, только пока витрина всё ещё дефолтная
     * (см. [CatalogRequest.isDefaultCatalogRequest]), иначе следующий переход на дефолт затравил
     * бы себя чужой отфильтрованной выдачей.
     */
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

    /** Единственная развилка экрана: есть запрос — ищем, нет — показываем витрину по фильтрам. */
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
                    // Кэшируем только ДЕФОЛТНУЮ витрину (без фильтров/жанра/сортировки —
                    // см. [isDefaultCatalogRequest]): она одна нужна как затравка следующего
                    // холодного старта и фоновому прогреву AppWarmup, выборка по фильтрам —
                    // сиюминутная и её кэшировать незачем.
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
                        // Не превращаем сетевой сбой в настоящее пустое состояние каталога.
                        it.copy(loading = false, error = first.message)
                    }
                    showServerRetryNotice()
                }
            }
        }
    }

    /**
     * Догрузка следующей страницы витрины. Идемпотентна: во время загрузки, после конца
     * каталога и в режиме поиска повторный вызов игнорируется — UI может дёргать её при
     * каждом подходе скролла к хвосту сетки.
     */
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
                    // При частичном ответе повторяем тот же номер страницы: иначе у упавшего
                    // типа образуется незаметная дыра, хотя остальные карточки уже показаны.
                    if (!next.data.hadErrors) catalogPage = page
                    exhaustedTypes = next.data.exhaustedTypes
                    updateState { s ->
                        val seen = s.catalogItems.mapTo(HashSet()) { it.id }
                        // filter гасит id, уже стоящие в сетке; distinctById — дубли внутри самой
                        // страницы (склейка типов): без этого две карточки с одним id роняют LazyGrid.
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

    /**
     * Грузит страницу [page] витрины для всех неисчерпанных типов текущего фильтра, помечает
     * кончившиеся типы и двигает [catalogPage]. Ошибка — только когда не ответил НИ один тип:
     * частичная выдача лучше пустой сетки.
     */
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
            val message = results.firstNotNullOfOrNull { (_, result) ->
                (result as? RequestResult.Error)?.message
            }
            RequestResult.Error(message)
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

/**
 * Жанр, диапазонные фильтры и сортировка поверх выдачи поиска: сам `search` ничего из этого не
 * принимает. Страну (по id) и 4K локально не проверить — в [Item] их нет, они остаются серверными
 * и на результаты поиска не влияют.
 */
private fun arrange(items: List<Item>, state: SearchState): List<Item> {
    val genreId = state.selectedGenreId
    val filtered = items.asSequence()
        .filter { item -> genreId == null || item.genres.any { it.id == genreId } }
        .filter { item -> item.matches(state.filters) }
        // distinctById: поиск умеет вернуть один id дважды — в сетке это дубль ключа и краш.
        .distinctBy { it.id }
        .toList()
    return sortLocally(filtered, state.sort)
}

/** Уникальность по id: ключ карточки в LazyGrid, и повтор роняет измерение сетки. */
private fun List<Item>.distinctById(): List<Item> = distinctBy { it.id }

/** Локальная проверка фильтров по полям, которые есть в [Item] (год, рейтинги, завершённость). */
private fun Item.matches(filters: CatalogFilters): Boolean {
    // Локальные копии: nullable-поля CatalogFilters лежат в другом модуле, и смарт-каст через
    // границу модуля невозможен — сравнивать надо через захваченное значение.
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

/**
 * Досортировка на клиенте. Набор карточек выбирает сервер, но по «Рейтингу» и «Году» он
 * сортирует по своим полям, а карточка показывает НАШ усреднённый рейтинг (IMDb+КП) и год —
 * без локального прохода первой в сетке стояла бы не та карточка, которую зритель видит лучшей.
 * У остальных ключей (просмотры, новизна, рейтинги КП/IMDb по отдельности) локального поля в
 * [Item] нет: там доверяем порядку сервера как есть, направление он тоже уже применил.
 */
private fun sortLocally(items: List<Item>, sort: SortOption): List<Item> {
    val comparator = when (sort.field) {
        CatalogSort.RATING -> compareBy<Item> { it.rating.external }
        CatalogSort.YEAR -> compareBy<Item> { it.year }
        else -> return items
    }
    return if (sort.ascending) items.sortedWith(comparator) else items.sortedWith(comparator.reversed())
}

/**
 * Склейка выдачи нескольких типов по кругу (для чипа «Все»). Простой `flatten()` дал бы
 * 20 фильмов, потом 20 сериалов — до аниме зритель не долистал бы никогда. Тот же приём
 * переиспользует [fetchDefaultCatalogItems] (прогрев) в этом же файле.
 */
private fun interleave(lists: List<List<Item>>): List<Item> {
    if (lists.size == 1) return lists.first()
    val depth = lists.maxOf { it.size }
    return (0 until depth).flatMap { index -> lists.mapNotNull { it.getOrNull(index) } }
}

/**
 * Последний успешно загруженный лёгкий снимок ДЕФОЛТНОЙ витрины каталога — офлайн-устойчивость и,
 * отдельно, затравка для фонового прогрева `AppWarmup` (см. [com.filmax.core.domain.common.LastValueCache]).
 *
 * Специально только дефолт (без фильтра/жанра/сортировки, см. [CatalogRequest.isDefaultCatalogRequest])
 * и никогда — состояние текущего поискового запроса: тот сиюминутный и кэшировать его как «то, что
 * покажет каталог по умолчанию» было бы неверно. Пишется из [SearchScreenModel] на каждый успешный
 * независимый источник (первая страница витрины / жанры / страны — см. [SearchScreenModel.loadCatalog]
 * и [SearchScreenModel.loadCatalogMetadata]); читается один раз, как затравка, при первом открытии
 * витрины (см. [SearchEvent.LoadCatalog]).
 */
data class CatalogSnapshot(
    val items: List<Item> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val countries: List<Country> = emptyList(),
)

private fun SearchState.asCatalogSnapshot(): CatalogSnapshot =
    CatalogSnapshot(items = catalogItems, genres = genres, countries = countries)

/**
 * Первая страница ДЕФОЛТНОЙ витрины (без фильтра/жанра, сортировка «Просмотры») — общая точка
 * входа для [fetchCatalogSnapshot] (прогрев). [SearchScreenModel] сам эту функцию не зовёт: его
 * [SearchScreenModel.fetchCatalogPage] — более общий путь с пагинацией/жанром/сортировкой/учётом
 * уже исчерпанных типов, здесь же нужна только первая страница дефолта, без остальной механики.
 */
private suspend fun fetchDefaultCatalogItems(catalog: CatalogRepository): List<Item> = coroutineScope {
    val pages = BrowseTypes.map { type ->
        async { catalog.getItems(type, null, CatalogFilters(), SortOption(CatalogSort.VIEWS)) }
    }.awaitAll().mapNotNull { it.getOrNull() }
    interleave(pages.map { it.items })
}

/**
 * Собирает [CatalogSnapshot] из сети напрямую — используется ТОЛЬКО фоновым прогревом `AppWarmup`
 * из модуля `:app` (см. `app/warmup/AppWarmup.kt`), который кладёт результат через `putIfAbsent`,
 * если витрина ещё ни разу не открывалась в этой сессии процесса — отсюда публичная видимость
 * (не `internal`: `:app` — отдельный Gradle-модуль, `internal` был бы ему не виден).
 */
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
