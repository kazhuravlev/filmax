package com.filmax.core.domain.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

enum class ItemCacheTtl(val days: Int?) {
    MONTH(30),
    WEEK(7),
    THREE_DAYS(3),

    NEVER(null),
}

interface ItemDetailsCache {
    suspend fun get(key: String): String?

    fun remember(key: String, json: String)

    fun rememberIfAbsent(key: String, json: String)

    suspend fun remove(key: String)

    val ttl: StateFlow<ItemCacheTtl>
    suspend fun setTtl(ttl: ItemCacheTtl)

    val count: StateFlow<Int>
    suspend fun clear()
}

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
