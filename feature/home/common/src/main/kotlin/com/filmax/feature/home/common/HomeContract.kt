package com.filmax.feature.home.common

import com.filmax.core.domain.catalog.model.Collection
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.domain.watching.model.Continuation

/**
 * Срез постранично догружаемого ряда. [page] — последняя загруженная страница каталога
 * (0 — в ряду только стартовая горстка из фида, каталог ещё не листали).
 */
data class RowPaging<T>(
    val items: List<T> = emptyList(),
    val page: Int = 0,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
)

/**
 * Ряд ленты. Состав и порядок ленты задаёт модель — экраны идут по [HomeState.rows] и рисуют
 * то, что пришло. Каждый ряд несёт всё нужное для своей отрисовки и догрузки сам на себе —
 * внешней статической мапы «id ряда → источник» больше нет (см. [HomeScreenModel]): ряды теперь
 * не фиксированный enum, а список, зашитый в [HOME_CATALOG_ROWS].
 *
 * [loading] — true, пока для ряда ещё не пришёл первый ответ сети в текущем проходе
 * [HomeScreenModel.onFetchData]: все источники стартуют параллельно, и экран рисует скелетон
 * вместо карточек, пока конкретный ряд ждёт свои данные — остальные рисуются, как только готовы,
 * не дожидаясь друг друга.
 */
sealed interface HomeRow {
    val id: String
    val title: String
    val loading: Boolean

    /** Пустой и уже загруженный ряд экран пропускает — заголовок без карточек хуже отсутствующего
     * ряда. Во время загрузки ряд НЕ считается пустым — иначе скелетон никогда бы не показался. */
    val isEmpty: Boolean

    /** «Продолжить» — история просмотра целиком, страниц у неё нет. */
    data class Continue(val entries: List<Continuation>, override val loading: Boolean) : HomeRow {
        override val id: String get() = "continue"
        override val title: String get() = "Продолжить просмотр"
        override val isEmpty: Boolean get() = !loading && entries.isEmpty()
    }

    /** Ряд тайтлов каталога. [types] — один тип почти всегда, два (movie+serial) только у «Аниме»
     * (см. [HOME_CATALOG_ROWS]) — жанр один на оба, как и в гайде сервера. */
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

    /** «Подборки» — свой источник и своя карточка; в гайде сервера этого ряда нет вообще
     * (см. [HOME_CATALOG_ROWS]), это отдельная витрина Filmax, оставлена последним рядом. */
    data class Collections(val paging: RowPaging<Collection>, override val loading: Boolean) : HomeRow {
        override val id: String get() = "collections"
        override val title: String get() = "Подборки"
        override val isEmpty: Boolean get() = !loading && paging.items.isEmpty()
    }
}

data class HomeState(
    val loading: Boolean = true,
    /** Инициалы текущего пользователя для аватара в шапке (пусто — пока не загружено). */
    val initials: String = "",
    val heroLoading: Boolean = true,
    val hero: Item? = null,
    val rows: List<HomeRow> = emptyList(),
    val error: String? = null,
) {
    /**
     * Показывать нечего: ни hero, ни одного непустого ряда, и всё уже отгрузилось (не в процессе).
     * Холодный старт без сети выглядит именно так — экран должен объясниться, а не остаться пустым.
     */
    val isEmpty: Boolean
        get() = !heroLoading && hero == null && rows.all { it.isEmpty }
}

sealed interface HomeEvent {
    data object Load : HomeEvent

    /**
     * Догрузить ряд. Экран зовёт, когда лента доехала до хвоста ряда; повторные вызовы гасит
     * идемпотентность модели, поэтому триггер может быть сколь угодно грубым.
     */
    data class LoadMoreRow(val id: String) : HomeEvent
}

/** Экран пока не порождает одноразовых эффектов — навигацию экраны просят у Navigator сами. */
sealed interface HomeSideEffect
