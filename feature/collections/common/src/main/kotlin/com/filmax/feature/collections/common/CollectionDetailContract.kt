package com.filmax.feature.collections.common

import com.filmax.core.domain.catalog.model.Item

data class CollectionDetailState(
    val loading: Boolean = true,
    val items: List<Item> = emptyList(),
    val page: Int = 0,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
)

sealed interface CollectionDetailEvent {
    data object LoadMore : CollectionDetailEvent
}

sealed interface CollectionDetailSideEffect
