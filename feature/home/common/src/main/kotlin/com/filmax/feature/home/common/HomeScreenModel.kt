package com.filmax.feature.home.common

import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.catalog.CatalogSort
import com.filmax.core.domain.catalog.model.Collection
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.usecase.home.GetHomeFeedUseCase
import com.filmax.core.domain.user.UserRepository
import com.filmax.core.domain.user.model.initials
import com.filmax.core.presentation.BaseScreenModel

class HomeScreenModel(
    private val getHomeFeed: GetHomeFeedUseCase,
    private val catalog: CatalogRepository,
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

    override fun onFetchData() {
        screenModelScope { _ ->
            updateState { it.copy(loading = true, error = null) }
            val feed = getHomeFeed()
            updateState { s ->
                // При частичном сбое сервер возвращает пустой список только для упавшей секции.
                // Не выдаём его за реальное «ничего нет»: оставляем прежнюю секцию до повтора.
                val preserveMissing = feed.error != null
                val oldContinue = s.rows.filterIsInstance<HomeRow.Continue>().firstOrNull()?.entries.orEmpty()
                val oldTrending = s.titleItems(HomeRowId.TRENDING)
                val oldForYou = s.titleItems(HomeRowId.FOR_YOU)
                val oldCollections = s.rows.filterIsInstance<HomeRow.Collections>()
                    .firstOrNull()?.paging?.items.orEmpty()
                s.copy(
                    loading = false,
                    hero = feed.hero ?: s.hero.takeIf { preserveMissing },
                    // Состав и порядок ленты — здесь и только здесь.
                    rows = listOf(
                        HomeRow.Continue(feed.continueWatching.preserveEmpty(oldContinue, preserveMissing)),
                        HomeRow.Titles(
                            HomeRowId.TRENDING,
                            RowPaging(feed.trending.preserveEmpty(oldTrending, preserveMissing)),
                        ),
                        HomeRow.Titles(
                            HomeRowId.FOR_YOU,
                            RowPaging(feed.forYou.preserveEmpty(oldForYou, preserveMissing)),
                        ),
                        HomeRow.Collections(
                            RowPaging(feed.collections.preserveEmpty(oldCollections, preserveMissing)),
                        ),
                    ),
                    error = feed.error,
                )
            }
            if (feed.error != null) scheduleServerRetry(::onFetchData)
            when {
                // Контент из кэша при офлайне (issue #42) — показываем баннер, не модалку.
                feed.fromCache -> showOfflineBanner()
                // Пусто + ошибка — блокирующая модалка.
                !feed.hasContent && feed.error != null -> showError(feed.error)
                // Свежие данные приехали — прячем баннер, если висел.
                else -> {
                    dismissOfflineBanner()
                    dismissError()
                }
            }
        }
    }

    /** Инициалы для аватара в шапке — best-effort, ошибки не мешают ленте. */
    private fun fetchUserInitials() {
        screenModelScope {
            (user.getProfile() as? RequestResult.Success)?.let { result ->
                updateState { it.copy(initials = result.data.initials()) }
            }
        }
    }

    private fun loadMoreRow(id: HomeRowId) {
        when (val row = state.rows.firstOrNull { it.id == id }) {
            // История приходит из фида целиком — листать нечего.
            is HomeRow.Continue, null -> Unit
            is HomeRow.Titles -> loadMoreTitles(row)
            is HomeRow.Collections -> loadMoreCollections(row)
        }
    }

    private fun loadMoreTitles(row: HomeRow.Titles) {
        val source = TITLE_SOURCES[row.id] ?: return
        if (!row.paging.canLoadMore) return
        val nextPage = row.paging.page + 1
        screenModelScope { _ ->
            updateTitles(row.id) { it.copy(loadingMore = true) }
            val result = catalog.getItems(source.type, source.sort, nextPage)
            when (result) {
                is RequestResult.Success -> updateTitles(row.id) {
                    it.append(result.data.items, Item::id, result.data.pagination.hasNextPage)
                }

                is RequestResult.Error -> updateTitles(row.id) { it.copy(loadingMore = false) }
            }
            if (result is RequestResult.Error) scheduleServerRetry { loadMoreRow(row.id) }
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
            updateCollections { it.copy(loadingMore = true) }
            val result = catalog.getCollections(nextPage)
            when (result) {
                is RequestResult.Success -> updateCollections {
                    it.append(result.data, Collection::id, hasNextPage = result.data.isNotEmpty())
                }

                is RequestResult.Error -> updateCollections { it.copy(loadingMore = false) }
            }
            if (result is RequestResult.Error) scheduleServerRetry { loadMoreRow(row.id) }
        }
    }

    private suspend fun updateTitles(id: HomeRowId, transform: (RowPaging<Item>) -> RowPaging<Item>) {
        updateRows { row ->
            if (row is HomeRow.Titles && row.id == id) row.copy(paging = transform(row.paging)) else row
        }
    }

    private suspend fun updateCollections(transform: (RowPaging<Collection>) -> RowPaging<Collection>) {
        updateRows { row ->
            if (row is HomeRow.Collections) row.copy(paging = transform(row.paging)) else row
        }
    }

    private suspend fun updateRows(transform: (HomeRow) -> HomeRow) {
        updateState { it.copy(rows = it.rows.map(transform)) }
    }
}

private fun HomeState.titleItems(id: HomeRowId): List<Item> =
    rows.filterIsInstance<HomeRow.Titles>().firstOrNull { it.id == id }?.paging?.items.orEmpty()

private fun <T> List<T>.preserveEmpty(previous: List<T>, preserve: Boolean): List<T> =
    if (preserve && isEmpty()) previous else this

/** Чем наполняется ряд тайтлов при догрузке: фид даёт стартовую горстку, дальше — каталог. */
private data class TitleSource(val type: ItemType, val sort: CatalogSort)

private val TITLE_SOURCES = mapOf(
    HomeRowId.TRENDING to TitleSource(ItemType.MOVIE, CatalogSort.VIEWS),
    HomeRowId.FOR_YOU to TitleSource(ItemType.SERIES, CatalogSort.RATING),
)

/**
 * Потолок карточек в одном ряду: бесконечный ряд на пульте — сотни нажатий вправо, а каждая
 * сотня карточек ещё и держит в памяти постеры. Дальше пусть зовёт Каталог.
 */
private const val HOME_ROW_MAX = 100

/** Ряд можно листать дальше: не занят, не кончился, не упёрся в потолок и вообще не пуст. */
private val RowPaging<*>.canLoadMore: Boolean
    get() = !loadingMore && !endReached && items.isNotEmpty() && items.size < HOME_ROW_MAX

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
