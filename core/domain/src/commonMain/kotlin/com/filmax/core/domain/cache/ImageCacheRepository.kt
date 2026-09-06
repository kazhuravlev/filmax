package com.filmax.core.domain.cache

import com.filmax.core.domain.tuning.PerformanceTuning
import kotlinx.coroutines.flow.StateFlow

/**
 * Кэш изображений (постеры, фоны, фото актёров) — единая точка сброса для настроек.
 * Хранение и срок жизни записей — забота реализации (см. `core:ui`, где живёт загрузчик картинок).
 *
 * [stats] — сколько сейчас реально лежит на диске, для подписи на кнопке сброса; см.
 * [ImageCacheStats] о том, откуда эти цифры берутся.
 */
interface ImageCacheRepository {
    val stats: StateFlow<ImageCacheStats>

    suspend fun clear()
}

/**
 * Живой размер дискового кэша картинок Coil: [sizeBytes] — сколько реально занято сейчас,
 * [maxSizeBytes] — настроенный потолок [PerformanceTuning.ImageCache.DISK_CACHE_MAX_SIZE_BYTES].
 * Читается напрямую из `coil3.disk.DiskCache.size`/`.maxSize` (реализация — `core:ui`), а не копится
 * счётчиком по фактам закачки: инкрементальный счётчик никогда не уменьшался, когда Coil тихо
 * вытеснял старые записи по лимиту размера, и на кнопке сброса показывал бы «сколько всего когда-либо
 * скачано», а не «что реально лежит на диске» — ровно то, что и путало пользователя.
 */
data class ImageCacheStats(val sizeBytes: Long = 0L, val maxSizeBytes: Long = 0L)
