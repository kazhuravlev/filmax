package com.filmax.core.ui.cache

import android.content.Context
import coil3.SingletonImageLoader
import com.filmax.core.domain.cache.ImageCacheRepository
import com.filmax.core.domain.cache.ImageCacheStats
import com.filmax.core.domain.cache.ImageCacheStatsRecorder
import com.filmax.core.domain.cache.ImageCacheStatsRecording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Чистит и памятный, и дисковый кэш общего Coil-загрузчика (см. `FilmaxImageLoaderFactory` в
 * `app`), а заодно ведёт компактную статистику того, что в кэше лежит — для подписи на кнопке
 * сброса в настройках.
 *
 * Статистика не сканирует диск: она копится инкрементально ([recordCached], зовёт OkHttp
 * network-interceptor из app-модуля через [ImageCacheStatsRecording] — сам он вне Koin-графа) и
 * персистится в SharedPreferences. Счётчик растёт при каждой реальной закачке и не уменьшается,
 * когда Coil тихо вытесняет старые записи по лимиту размера диска
 * (`FilmaxImageLoaderFactory.IMAGE_DISK_CACHE_MAX_SIZE_BYTES`) — обнуляет его только явный сброс
 * кэша. Для подписи на кнопке этого достаточно: там нужен порядок величины перед сбросом, а не
 * точное «сколько байт реально лежит на диске прямо сейчас».
 */
internal class ImageCacheRepositoryImpl(
    private val context: Context,
) : ImageCacheRepository, ImageCacheStatsRecorder {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val statsState = MutableStateFlow(
        ImageCacheStats(
            fileCount = prefs.getInt(KEY_COUNT, 0),
            totalBytes = prefs.getLong(KEY_BYTES, 0L),
        ),
    )

    override val stats: StateFlow<ImageCacheStats> = statsState.asStateFlow()

    init {
        ImageCacheStatsRecording.recorder = this
    }

    @Synchronized
    override fun recordCached(bytes: Long) {
        if (bytes <= 0) return
        val updated = statsState.value.let {
            ImageCacheStats(fileCount = it.fileCount + 1, totalBytes = it.totalBytes + bytes)
        }
        prefs.edit()
            .putInt(KEY_COUNT, updated.fileCount)
            .putLong(KEY_BYTES, updated.totalBytes)
            .apply()
        statsState.value = updated
    }

    override suspend fun clear() {
        val imageLoader = SingletonImageLoader.get(context)
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
        prefs.edit().clear().apply()
        statsState.value = ImageCacheStats()
    }

    private companion object {
        const val PREFS_NAME = "filmax_image_cache_stats"
        const val KEY_COUNT = "file_count"
        const val KEY_BYTES = "total_bytes"
    }
}
