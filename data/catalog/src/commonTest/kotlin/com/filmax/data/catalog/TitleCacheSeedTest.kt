package com.filmax.data.catalog

import com.filmax.core.domain.cache.DiscoveredTitle
import com.filmax.core.domain.cache.ItemCacheTtl
import com.filmax.core.domain.cache.ItemDetailsCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TitleCacheSeedTest {

    @Test
    fun `preview is stored with put-if-absent before background enrichment`() {
        val cache = RecordingItemCache()

        rememberPreview(DiscoveredTitle(id = 42, previewJson = "{\"id\":42}"), cache)

        assertEquals(listOf("item:42" to "{\"id\":42}"), cache.previews)
        assertTrue(cache.replacements.isEmpty(), "a list preview must never replace full details")
    }

    private class RecordingItemCache : ItemDetailsCache {
        val previews = mutableListOf<Pair<String, String>>()
        val replacements = mutableListOf<Pair<String, String>>()

        override suspend fun get(key: String): String? = null

        override fun remember(key: String, json: String) {
            replacements += key to json
        }

        override fun rememberIfAbsent(key: String, json: String) {
            previews += key to json
        }

        override suspend fun remove(key: String) = Unit
        override val ttl: StateFlow<ItemCacheTtl> = MutableStateFlow(ItemCacheTtl.MONTH)
        override suspend fun setTtl(ttl: ItemCacheTtl) = Unit
        override val count: StateFlow<Int> = MutableStateFlow(0)
        override suspend fun clear() = Unit
    }
}
