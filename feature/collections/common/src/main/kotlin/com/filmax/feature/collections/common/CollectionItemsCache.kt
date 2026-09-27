package com.filmax.feature.collections.common

import com.filmax.core.domain.catalog.model.Item

object CollectionItemsCache {
    private val cache = mutableMapOf<Int, List<Item>>()

    @Synchronized
    fun get(collectionId: Int): List<Item>? = cache[collectionId]

    @Synchronized
    fun put(collectionId: Int, items: List<Item>) {
        cache[collectionId] = items
    }
}
