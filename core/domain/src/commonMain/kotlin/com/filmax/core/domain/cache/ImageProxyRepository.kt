package com.filmax.core.domain.cache

import kotlinx.coroutines.flow.StateFlow

/**
 * Прокси для загрузки изображений (постеры, фото актёров): часть сетей нестабильно ходит
 * напрямую к CDN kino.watch/TMDB, прокси-воркер отдаёт то же изображение надёжнее. Настройка
 * персистентная и по умолчанию включена — пункт «Прокси изображений» в настройках лишь даёт
 * её выключить.
 *
 * Кэш картинок при переключении не рвётся: он ключуется не по итоговому URL (прямому или через
 * прокси), а по стабильному идентификатору тайтла/актёра — см. `ImageCacheKeys`/`CacheableImage`
 * в `core:ui`.
 */
interface ImageProxyRepository {
    val enabled: StateFlow<Boolean>
    suspend fun setEnabled(enabled: Boolean)
}
