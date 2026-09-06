package com.filmax.feature.collections.common

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.toRoute
import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.common.getOrNull
import com.filmax.core.presentation.BaseScreenModel
import com.filmax.feature.collections.common.navigation.CollectionDetailRoute

class CollectionDetailScreenModel(
    savedStateHandle: SavedStateHandle,
    private val catalog: CatalogRepository,
) : BaseScreenModel<CollectionDetailState, CollectionDetailSideEffect, CollectionDetailEvent>(
    CollectionDetailState(),
) {

    private val route = savedStateHandle.toRoute<CollectionDetailRoute>()

    init {
        onFetchData()
    }

    override fun dispatch(event: CollectionDetailEvent) {
        when (event) {
            CollectionDetailEvent.LoadMore -> loadMore()
        }
    }

    /**
     * ScreenModel пересоздаётся на каждый заход в подборку (в отличие, например, от
     * `LibraryScreenModel`), поэтому без [CollectionItemsCache] повторное открытие той же
     * подборки всегда рисовало бы полноэкранный спиннер поверх того, что зритель только что видел.
     * Кэш красит сетку мгновенно, а первая страница тут же тихо перечитывается с сервера —
     * подборку могли изменить с другого экрана, и кэш лишь «быстрая картинка», а не повод
     * пропустить запрос. Полноэкранный спиннер остаётся только когда кэша нет вовсе.
     */
    override fun onFetchData() {
        val cached = CollectionItemsCache.get(route.collectionId)
        screenModelScope { _ ->
            updateState { it.copy(loading = cached == null, items = cached ?: it.items, error = null) }
            when (val result = catalog.getCollectionItems(route.collectionId, page = FIRST_PAGE)) {
                is RequestResult.Success -> {
                    CollectionItemsCache.put(route.collectionId, result.data.items)
                    updateState {
                        it.copy(
                            loading = false,
                            items = result.data.items,
                            page = FIRST_PAGE,
                            endReached = !result.data.pagination.hasNextPage,
                            error = null,
                        )
                    }
                    dismissError()
                }

                is RequestResult.Error -> if (cached == null) {
                    updateState { it.copy(loading = false, error = result.message) }
                    showError(result)
                    showServerRetryNotice()
                }
                // cached != null: контент уже показан из кэша — тихий сбой ревалидации не должен
                // ни стирать его, ни тревожить уведомлением; следующее открытие попробует снова.
            }
        }
    }

    /**
     * Догрузка следующей страницы — тот же приём, что и `LibraryScreenModel.loadMoreFolderItems`:
     * идемпотентна (повторный вызов во время загрузки/после конца списка — no-op), дедуплицирует
     * по id (страницы kino.watch могут пересечься — иначе дубль id уронил бы LazyGrid по key), а
     * при сбое не двигает счётчик страницы, чтобы следующая попытка повторила тот же номер.
     */
    private fun loadMore() {
        val current = state
        if (current.loading || current.loadingMore || current.endReached) return
        val nextPage = current.page + 1
        screenModelScope { _ ->
            updateState { it.copy(loadingMore = true) }
            val result = catalog.getCollectionItems(route.collectionId, page = nextPage)
            val itemPage = result.getOrNull()
            updateState { s ->
                s.copy(
                    items = (s.items + itemPage?.items.orEmpty()).distinctBy { it.id },
                    page = if (itemPage != null) nextPage else s.page,
                    loadingMore = false,
                    endReached = itemPage?.pagination?.hasNextPage?.not() ?: s.endReached,
                )
            }
            if (itemPage != null) CollectionItemsCache.put(route.collectionId, state.items)
            if (result is RequestResult.Error) showServerRetryNotice()
        }
    }

    private companion object {
        /** Первая страница подборки (нумерация kino.watch — с единицы). */
        const val FIRST_PAGE = 1
    }
}
