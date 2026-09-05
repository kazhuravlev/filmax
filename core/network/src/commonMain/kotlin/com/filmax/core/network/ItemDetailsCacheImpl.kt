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

/**
 * Персистентный кэш статической информации о тайтлах (`items/{id}`) — на отдельном [Settings]
 * (см. `ITEM_CACHE_SETTINGS` в DI), не на общем с [TokenStorage]: кэш может разрастись до сотен
 * записей, и `clear()` в [clear] не должен разлогинивать пользователя заодно. [count] не
 * пересчитывается сканированием ключей — растёт на единицу в [remember] только для
 * по-настоящему нового id, что и даёт «не онлайн» счётчик для кнопки «Сбросить кэш» в настройках.
 */
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

    override suspend fun get(itemId: Int): String? {
        val maxAgeDays = ttlState.value.days ?: return null
        val cachedAt = settings.getLongOrNull(PREFIX_TIMESTAMP + itemId)
        val isFresh = cachedAt != null && currentTimeMillis() - cachedAt <= maxAgeDays * MILLIS_PER_DAY
        return settings.getStringOrNull(PREFIX_JSON + itemId).takeIf { isFresh }
    }

    override fun remember(itemId: Int, json: String) {
        if (ttlState.value == ItemCacheTtl.NEVER) return
        val jsonKey = PREFIX_JSON + itemId
        val isNewEntry = settings.getStringOrNull(jsonKey) == null
        settings.putString(jsonKey, json)
        settings.putLong(PREFIX_TIMESTAMP + itemId, currentTimeMillis())
        if (isNewEntry) {
            val updated = countState.value + 1
            countState.value = updated
            settings.putInt(KEY_COUNT, updated)
        }
    }

    override suspend fun setTtl(ttl: ItemCacheTtl) {
        settings.putString(KEY_TTL, ttl.name)
        ttlState.value = ttl
    }

    override suspend fun clear() {
        // settings — отдельный файл (ITEM_CACHE_SETTINGS), только под этот кэш: clear() не задевает
        // токены/остальные настройки. TTL — настройка, а не данные кэша, поэтому переписываем её
        // обратно сразу после очистки: «Сбросить кэш» не должно тихо возвращать TTL к «Месяц».
        val currentTtl = ttlState.value
        settings.clear()
        settings.putString(KEY_TTL, currentTtl.name)
        countState.value = 0
    }
}
