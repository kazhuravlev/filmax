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
            }
        }
    }

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
        const val FIRST_PAGE = 1
    }
}
