package com.filmax.core.domain.cache

import kotlinx.coroutines.flow.StateFlow

interface ImageCacheRepository {
    val stats: StateFlow<ImageCacheStats>

    suspend fun clear()
}

data class ImageCacheStats(val sizeBytes: Long = 0L, val maxSizeBytes: Long = 0L)
