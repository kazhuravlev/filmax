package com.filmax.core.ui.cache

import android.content.Context
import coil3.SingletonImageLoader
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

/**
 * Чистит и памятный, и дисковый кэш общего Coil-загрузчика (см. `FilmaxImageLoaderFactory` в
 * `app`), а заодно читает его текущий размер — для подписи на кнопке сброса в настройках.
 *
 * [stats] — живое чтение `coil3.disk.DiskCache.size`/`.maxSize`, а не счётчик по фактам закачки:
 * Coil сам ведёт эти два числа в памяти (никакого сканирования диска на каждый показ настроек),
 * и, в отличие от инкрементального счётчика, они не расходятся с реальностью, когда Coil тихо
 * вытесняет старые записи по лимиту размера. Опрашиваем раз в [STATS_REFRESH_INTERVAL_MS] — дешёвое
 * чтение поля, а не I/O — пока кто-то подписан (обычно только открытый экран настроек).
 */
internal class ImageCacheRepositoryImpl(
    private val context: Context,
) : ImageCacheRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statsState = MutableStateFlow(readStats())

    override val stats: StateFlow<ImageCacheStats> = statsState.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                delay(STATS_REFRESH_INTERVAL_MS)
                statsState.value = readStats()
            }
        }
    }

    override suspend fun clear() {
        val imageLoader = SingletonImageLoader.get(context)
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
        statsState.value = readStats()
    }

    private fun readStats(): ImageCacheStats {
        val diskCache = SingletonImageLoader.get(context).diskCache ?: return ImageCacheStats()
        return ImageCacheStats(sizeBytes = diskCache.size, maxSizeBytes = diskCache.maxSize)
    }

    private companion object {
        const val STATS_REFRESH_INTERVAL_MS = 3_000L
    }
}
