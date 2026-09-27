package com.filmax.core.network

import com.filmax.core.domain.cache.ItemCacheTtl
import com.filmax.core.domain.cache.ItemDetailsCache
import com.filmax.core.domain.cache.ItemDetailsCacheAccess
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val KEY_TTL = "item_cache_ttl"
private const val KEY_COUNT = "item_cache_count"
private const val PREFIX_JSON = "item_cache_json:"
private const val PREFIX_TIMESTAMP = "item_cache_ts:"
private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

class ItemDetailsCacheImpl(private val settings: Settings) : ItemDetailsCache {
    private val ttlState = MutableStateFlow(
        settings.getStringOrNull(KEY_TTL)?.let { name ->
            runCatching { ItemCacheTtl.valueOf(name) }.getOrNull()
        } ?: ItemCacheTtl.MONTH,
    )
    private val countState = MutableStateFlow(settings.getInt(KEY_COUNT, 0))

    override val ttl: StateFlow<ItemCacheTtl> = ttlState.asStateFlow()
    override val count: StateFlow<Int> = countState.asStateFlow()

    init {
        ItemDetailsCacheAccess.cache = this
    }

    override suspend fun get(key: String): String? {
        val maxAgeDays = ttlState.value.days ?: return null
        val cachedAt = settings.getLongOrNull(PREFIX_TIMESTAMP + key)
        val isFresh = cachedAt != null && currentTimeMillis() - cachedAt <= maxAgeDays * MILLIS_PER_DAY
        return settings.getStringOrNull(PREFIX_JSON + key).takeIf { isFresh }
    }

    override fun remember(key: String, json: String) {
        if (ttlState.value == ItemCacheTtl.NEVER) return
        val jsonKey = PREFIX_JSON + key
        val isNewEntry = settings.getStringOrNull(jsonKey) == null
        settings.putString(jsonKey, json)
        settings.putLong(PREFIX_TIMESTAMP + key, currentTimeMillis())
        if (isNewEntry) {
            val updated = countState.value + 1
            countState.value = updated
            settings.putInt(KEY_COUNT, updated)
        }
    }

    override fun rememberIfAbsent(key: String, json: String) {
        if (ttlState.value == ItemCacheTtl.NEVER) return
        val jsonKey = PREFIX_JSON + key
        if (settings.getStringOrNull(jsonKey) != null) return

        settings.putString(jsonKey, json)
        settings.putLong(PREFIX_TIMESTAMP + key, currentTimeMillis())
        val updated = countState.value + 1
        countState.value = updated
        settings.putInt(KEY_COUNT, updated)
    }

    override suspend fun remove(key: String) {
        val jsonKey = PREFIX_JSON + key
        val existed = settings.getStringOrNull(jsonKey) != null
        settings.remove(jsonKey)
        settings.remove(PREFIX_TIMESTAMP + key)
        if (existed) {
            val updated = (countState.value - 1).coerceAtLeast(0)
            countState.value = updated
            settings.putInt(KEY_COUNT, updated)
        }
    }

    override suspend fun setTtl(ttl: ItemCacheTtl) {
        settings.putString(KEY_TTL, ttl.name)
        ttlState.value = ttl
    }

    override suspend fun clear() {
        val currentTtl = ttlState.value
        settings.clear()
        settings.putString(KEY_TTL, currentTtl.name)
        countState.value = 0
    }
}
