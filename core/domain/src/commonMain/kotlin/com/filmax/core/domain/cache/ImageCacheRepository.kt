package com.filmax.core.domain.cache

/**
 * Кэш изображений (постеры, фоны, фото актёров) — единая точка сброса для настроек.
 * Хранение и срок жизни записей — забота реализации (см. `core:ui`, где живёт загрузчик картинок).
 */
interface ImageCacheRepository {
    suspend fun clear()
}
