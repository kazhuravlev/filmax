package com.filmax.core.ui.cache

/**
 * Модель для Coil, которая кэширует не по [url], а по стабильному [key] вида
 * `entityType:entityId:subId` (например `movie:123:poster_medium`, см. [ImageCacheKeys]). Так
 * смена источника картинки — прямая ссылка или через image-прокси, либо смена самого
 * прокси-сервера — не роняет и не дублирует кэш: сервер раздачи может смениться, а ключ той же
 * картинки тайтла остаётся прежним.
 *
 * Регистрируется в Coil парой `Keyer`+`Mapper` (см. `FilmaxImageLoaderFactory` в `app`): `Keyer`
 * отдаёт [key] как есть, `Mapper` разворачивает модель обратно в [url] для реальной загрузки.
 */
data class CacheableImage(val key: String, val url: String)
