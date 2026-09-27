package com.filmax.feature.home.common

import com.filmax.core.domain.catalog.model.Collection
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.domain.watching.model.Continuation

data class RowPaging<T>(
    val items: List<T> = emptyList(),
    val page: Int = 0,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
)

sealed interface HomeRow {
    val id: String
    val title: String
    val loading: Boolean

    val isEmpty: Boolean

    data class Continue(val entries: List<Continuation>, override val loading: Boolean) : HomeRow {
        override val id: String get() = "continue"
        override val title: String get() = "Продолжить просмотр"
        override val isEmpty: Boolean get() = !loading && entries.isEmpty()
    }

    data class Titles(
        override val id: String,
        override val title: String,
        val types: List<ItemType>,
        val genreId: Int?,
        override val loading: Boolean,
        val paging: RowPaging<Item>,
    ) : HomeRow {
        override val isEmpty: Boolean get() = !loading && paging.items.isEmpty()
    }

    data class Collections(val paging: RowPaging<Collection>, override val loading: Boolean) : HomeRow {
        override val id: String get() = "collections"
        override val title: String get() = "Подборки"
        override val isEmpty: Boolean get() = !loading && paging.items.isEmpty()
    }
}

data class HomeState(
    val loading: Boolean = true,
    val initials: String = "",
    val heroLoading: Boolean = true,
    val hero: Item? = null,
    val rows: List<HomeRow> = emptyList(),
    val error: String? = null,
) {
    val isEmpty: Boolean
        get() = !heroLoading && hero == null && rows.all { it.isEmpty }
}

sealed interface HomeEvent {
    data object Load : HomeEvent

    data class LoadMoreRow(val id: String) : HomeEvent
}

sealed interface HomeSideEffect
