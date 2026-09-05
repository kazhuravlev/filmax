package com.filmax.core.ui.cache

import android.content.Context
import coil3.SingletonImageLoader
import com.filmax.core.domain.cache.ImageCacheRepository

/** Чистит и памятный, и дисковый кэш общего Coil-загрузчика (см. `FilmaxImageLoaderFactory` в `app`). */
internal class ImageCacheRepositoryImpl(private val context: Context) : ImageCacheRepository {
    override suspend fun clear() {
        val imageLoader = SingletonImageLoader.get(context)
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    }
}
