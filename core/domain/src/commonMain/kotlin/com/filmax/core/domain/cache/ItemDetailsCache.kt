package com.filmax.core.domain.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

/**
 * Срок жизни закэшированной статической информации о тайтле (название, описание, актёры,
 * режиссёр, жанры, рейтинги, трейлер, факт «в подборках») — она почти не меняется, поэтому
 * жёсткое кэширование по умолчанию на месяц безопасно.
 */
enum class ItemCacheTtl(val days: Int?) {
    MONTH(30),
    WEEK(7),
    THREE_DAYS(3),

    /** Кэш выключен: [ItemDetailsCache.get] всегда промах, [ItemDetailsCache.remember] — no-op. */
    NEVER(null),
}

/**
 * Кэш «статической» информации о тайтлах (`items/{id}`, `items/similar`) — независимый от кэша
 * картинок. Хранит сырой JSON (формат и ключ решает вызывающий, см. `data:catalog`), не доменную
 * модель: так `core:domain` не тянет зависимость на `kotlinx.serialization` ради одного этого
 * кэша. [key] — строка вида `item:123`/`similar:123`, а не голый id: под одним хранилищем и
 * счётчиком уживаются оба разных по смыслу кэша (сами детали тайтла и список похожих на него).
 *
 * [count] — не результат сканирования хранилища на каждый рендер настроек, а персистентный
 * счётчик, который реализация инкрементит сама в момент первой записи нового ключа (см.
 * `ItemDetailsCacheImpl` в core:network).
 */
interface ItemDetailsCache {
    suspend fun get(key: String): String?

    /** Синхронная, не suspend: вызывается из чистого маппера (`ItemDto.toDomain()`) без корутины. */
    fun remember(key: String, json: String)

    /**
     * Сохраняет облегчённый ответ списка, только если по ключу ещё ничего нет. Полный
     * `items/{id}` нельзя затирать более бедной карточкой из очередного списка.
     */
    fun rememberIfAbsent(key: String, json: String)

    /**
     * Точечная инвалидация одного ключа — когда о тайтле стало известно что-то, чего кэш ещё не
     * знает (например, локальное действие пользователя: подборки, «буду смотреть»), и ждать TTL
     * нельзя. Следующий [get] по этому ключу — гарантированный промах, дальше система сама
     * перечитает и перезапишет кэш свежими данными (см. вызывающих в `data:catalog`).
     */
    suspend fun remove(key: String)

    val ttl: StateFlow<ItemCacheTtl>
    suspend fun setTtl(ttl: ItemCacheTtl)

    val count: StateFlow<Int>
    suspend fun clear()
}

/**
 * Точка доступа вне DI-графа. Полные ответы `ItemDto.toDomain()` заменяют сохранённое значение
 * напрямую, а preview списков проходит через `TitleBackgroundFetcherImpl` и
 * [ItemDetailsCache.rememberIfAbsent], чтобы никогда не затереть полный треклист.
 */
object ItemDetailsCacheAccess {
    @Volatile
    var cache: ItemDetailsCache = NoopItemDetailsCache
}

private object NoopItemDetailsCache : ItemDetailsCache {
    override suspend fun get(key: String): String? = null
    override fun remember(key: String, json: String) = Unit
    override fun rememberIfAbsent(key: String, json: String) = Unit
    override suspend fun remove(key: String) = Unit
    override val ttl: StateFlow<ItemCacheTtl> = MutableStateFlow(ItemCacheTtl.MONTH)
    override suspend fun setTtl(ttl: ItemCacheTtl) = Unit
    override val count: StateFlow<Int> = MutableStateFlow(0)
    override suspend fun clear() = Unit
}
