package com.filmax.core.domain.cache

import kotlin.concurrent.Volatile

/** Одна картинка для тихой фоновой закачки: стабильный ключ (см. [ImageCacheKeys]) + текущий адрес. */
data class PrefetchImage(val key: String, val url: String)

/** Очередь фоновой закачки картинок в кэш. Порядок не гарантирован явно, но реализация обрабатывает
 * его последовательно (одна закачка за раз) — см. `ImagePrefetcherImpl` в core:ui. */
fun interface ImagePrefetcher {
    fun enqueue(images: List<PrefetchImage>)
}

/**
 * Точка обнаружения картинок вне DI-графа: DTO→domain мапперы (модули data, например
 * `ItemDto.toDomain()`) и экраны без доступа к core:ui шлют сюда всё, что «увидели» — постеры
 * тайтла, фото актёров из сырой строки `cast` — не дожидаясь, пока пользователь реально откроет
 * экран с этой картинкой. Реализацию (реальную очередь на Coil) подставляет `core:ui` при старте,
 * аналогично [com.filmax.core.domain.common.ErrorReporting]/[com.filmax.core.domain.common.ConnectionFailures].
 * До подстановки — no-op, чтобы вызовы из data-слоя были безопасны в тестах и до старта DI.
 */
object ImageDiscovery {
    @Volatile
    var prefetcher: ImagePrefetcher = ImagePrefetcher {}

    fun discovered(images: List<PrefetchImage>) {
        if (images.isNotEmpty()) prefetcher.enqueue(images)
    }
}
