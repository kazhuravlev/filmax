package com.filmax.core.ui.cache

import android.content.Context
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import com.filmax.core.domain.cache.ImageCacheRepository
import com.filmax.core.domain.cache.ImageCacheStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class ImageCacheRepositoryImpl(
    private val context: Context,
    private val diskCacheProvider: () -> DiskCache? = { SingletonImageLoader.get(context).diskCache },
    private val clearCaches: () -> Unit = {
        val imageLoader = SingletonImageLoader.get(context)
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    },
) : ImageCacheRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statsState = MutableStateFlow(ImageCacheStats())

    override val stats: StateFlow<ImageCacheStats> = statsState.asStateFlow()

    init {
        scope.launch {
            statsState.value = readStats()
            while (isActive) {
                delay(STATS_REFRESH_INTERVAL_MS)
                statsState.value = readStats()
            }
        }
    }

    override suspend fun clear() {
        clearCaches()
        statsState.value = readStats()
    }

    private fun readStats(): ImageCacheStats {
        val diskCache = diskCacheProvider() ?: return ImageCacheStats()
        return ImageCacheStats(sizeBytes = diskCache.size, maxSizeBytes = diskCache.maxSize)
    }

    private companion object {
        const val STATS_REFRESH_INTERVAL_MS = 3_000L
    }
}
