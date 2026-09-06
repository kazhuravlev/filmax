package com.filmax.feature.collections.common

import com.filmax.core.domain.catalog.model.Item

data class CollectionDetailState(
    val loading: Boolean = true,
    val items: List<Item> = emptyList(),
    /** Последняя загруженная страница подборки (0 — ещё ни одной, например при сбое первой). */
    val page: Int = 0,
    /** Идёт догрузка следующей страницы (хвостовой индикатор сетки), отдельно от [loading]. */
    val loadingMore: Boolean = false,
    /** Страницы подборки кончились — больше не грузим. */
    val endReached: Boolean = false,
    val error: String? = null,
)

sealed interface CollectionDetailEvent {
    /** Догрузить следующую страницу подборки (триггерится при подходе скролла к концу сетки). */
    data object LoadMore : CollectionDetailEvent
}

sealed interface CollectionDetailSideEffect
