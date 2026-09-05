package com.filmax.app.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Path.Companion.toOkioPath

/**
 * Общий загрузчик картинок на всё приложение — постеры, фоны, фото актёров: все они идут через
 * `PosterImage` (core:ui), а тот берёт синглтон-загрузчик Coil без явного `ImageLoader`. Это
 * единственное место, где этот синглтон настраивается (см. `FilmaxApplication`, реализующую
 * `SingletonImageLoader.Factory` через делегирование сюда).
 */
class FilmaxImageLoaderFactory : SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .addNetworkInterceptor(ImageCacheLifetimeInterceptor())
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcher.factory(callFactory = { okHttpClient })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve(IMAGE_DISK_CACHE_DIR).toOkioPath())
                    .maxSizeBytes(IMAGE_DISK_CACHE_MAX_SIZE_BYTES)
                    .build()
            }
            .build()
    }
}

/**
 * kino.watch (и TMDB для фото актёров) не всегда шлют внятный `Cache-Control` — а постеры и кадры
 * тайтла не меняются годами. Поэтому сами проставляем срок жизни в 30 дней вместо серверного:
 * так дисковый кэш Coil реально держит картинки этот срок, а не перекачивает их на каждый холодный
 * старт. `addNetworkInterceptor`, а не `addInterceptor` — переписывать нужно ответ РЕАЛЬНОЙ сети,
 * до того как OkHttp/Coil решат, что с ним кэшировать.
 */
private class ImageCacheLifetimeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        return response.newBuilder()
            .removeHeader(HEADER_PRAGMA)
            .removeHeader(HEADER_CACHE_CONTROL)
            .header(HEADER_CACHE_CONTROL, "public, max-age=$IMAGE_CACHE_MAX_AGE_SECONDS")
            .build()
    }
}

private const val HEADER_PRAGMA = "Pragma"
private const val HEADER_CACHE_CONTROL = "Cache-Control"

/** Отдельная от `UPDATES_DIR` (APK обновлений, см. `GitHubUpdateRepository`) поддиректория кэша. */
private const val IMAGE_DISK_CACHE_DIR = "image_cache"
private const val IMAGE_DISK_CACHE_MAX_SIZE_BYTES = 250L * 1024 * 1024
private const val IMAGE_CACHE_MAX_AGE_SECONDS = 30L * 24 * 60 * 60
