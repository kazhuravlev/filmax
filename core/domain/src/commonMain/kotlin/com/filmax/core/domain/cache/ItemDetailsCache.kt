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
 * Кэш «статической» информации о тайтлах (`items/{id}`) — независимый от кэша картинок. Хранит
 * сырой JSON (формат решает вызывающий, см. `data:catalog`), не доменную модель: так `core:domain`
 * не тянет зависимость на `kotlinx.serialization` ради одного этого кэша.
 *
 * [count] — не результат сканирования хранилища на каждый рендер настроек, а персистентный
 * счётчик, который реализация инкрементит сама в момент первой записи нового id (см.
 * `ItemDetailsCacheImpl` в core:network).
 */
interface ItemDetailsCache {
    suspend fun get(itemId: Int): String?

    /** Синхронная, не suspend: вызывается из чистого маппера (`ItemDto.toDomain()`) без корутины. */
    fun remember(itemId: Int, json: String)

    val ttl: StateFlow<ItemCacheTtl>
    suspend fun setTtl(ttl: ItemCacheTtl)

    val count: StateFlow<Int>
    suspend fun clear()
}

/**
 * Точка обнаружения вне DI-графа — `ItemDto.toDomain()` (`data:catalog`) кладёт в кэш КАЖДЫЙ
 * тайтл, который когда-либо пришёл от API (список, поиск, похожее, детали), не только те,
 * что пользователь открыл явно. Аналогично [ImageDiscovery]/[com.filmax.core.domain.common.ErrorReporting].
 */
object ItemDetailsCacheAccess {
    @Volatile
    var cache: ItemDetailsCache = NoopItemDetailsCache
}

private object NoopItemDetailsCache : ItemDetailsCache {
    override suspend fun get(itemId: Int): String? = null
    override fun remember(itemId: Int, json: String) = Unit
    override val ttl: StateFlow<ItemCacheTtl> = MutableStateFlow(ItemCacheTtl.MONTH)
    override suspend fun setTtl(ttl: ItemCacheTtl) = Unit
    override val count: StateFlow<Int> = MutableStateFlow(0)
    override suspend fun clear() = Unit
}
