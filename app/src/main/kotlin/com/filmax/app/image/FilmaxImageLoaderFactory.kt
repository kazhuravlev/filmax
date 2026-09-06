package com.filmax.app.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.key.Keyer
import coil3.map.Mapper
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.filmax.core.domain.cache.ImagePrefetchThrottle
import com.filmax.core.domain.cache.NetworkStats
import com.filmax.core.domain.tuning.PerformanceTuning
import com.filmax.core.ui.cache.BACKGROUND_FETCH_HEADER
import com.filmax.core.ui.cache.CacheableImage
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Path.Companion.toOkioPath
import okio.buffer
import java.util.concurrent.TimeUnit

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
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
                // CacheableImage кэшируется по entity-ключу, а не по url — Keyer перехватывает
                // вычисление ключа раньше, чем Mapper развернёт модель обратно в строку для
                // реальной загрузки через уже зарегистрированный OkHttp-фетчер. См. CacheableImage.
                add(Keyer<CacheableImage> { data, _ -> data.key })
                add(Mapper<CacheableImage, String> { data, _ -> data.url })
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve(IMAGE_DISK_CACHE_DIR).toOkioPath())
                    .maxSizeBytes(PerformanceTuning.ImageCache.DISK_CACHE_MAX_SIZE_BYTES)
                    .build()
            }
            // TV-боксы имеют маленькую кучу, дефолтные 25% + полноразмерные декоды приводили к
            // OOM при скролле — явно урезаем до 15% вместо дефолта Coil.
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, PerformanceTuning.ImageCache.MEMORY_CACHE_SIZE_PERCENT)
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
 *
 * Здесь же придушиваем фоновую закачку ([BACKGROUND_FETCH_HEADER], см. `ImagePrefetcherImpl`) —
 * но не всегда, а только пока недавно было что-то ещё ([ImagePrefetchThrottle.shouldThrottle]):
 * обычный запрос экрана, запрос основного API-клиента или активное воспроизведение в плеере.
 * Простаивает приложение — фоновая очередь идёт на полной скорости, наравне с обычными запросами;
 * как только появляется другая активность, следующие 10 секунд фоновая закачка придушена, чтобы
 * не отъедать канал у того, что реально нужно пользователю прямо сейчас. Заголовок снимается перед
 * отправкой на сервер в любом случае — до него не доезжает.
 *
 * Заодно считаем реально прочитанные байты КАЖДОГО тела ответа (и обычной загрузки, и фоновой) в
 * [NetworkStats] — источник строки «сеть» в оверлее «Показывать технические данные» (см.
 * `TechOverlay`, app). Оборачиваем [CountingResponseBody] всегда, throttle (когда он нужен) идёт
 * уже поверх неё отдельным слоем — оба декоратора независимы друг от друга.
 */
private class ImageCacheLifetimeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val isBackgroundFetch = originalRequest.header(BACKGROUND_FETCH_HEADER) != null
        if (!isBackgroundFetch) ImagePrefetchThrottle.touch()
        val outgoingRequest = if (isBackgroundFetch) {
            originalRequest.newBuilder().removeHeader(BACKGROUND_FETCH_HEADER).build()
        } else {
            originalRequest
        }

        val response = chain.proceed(outgoingRequest)
            .newBuilder()
            .removeHeader(HEADER_PRAGMA)
            .removeHeader(HEADER_CACHE_CONTROL)
            .header(HEADER_CACHE_CONTROL, "public, max-age=${PerformanceTuning.ImageCache.MAX_AGE_SECONDS}")
            .build()

        val body = response.body ?: return response
        val countingBody = CountingResponseBody(body)
        val shouldThrottleBody = response.code == HTTP_OK && isBackgroundFetch && ImagePrefetchThrottle.shouldThrottle
        val finalBody = if (shouldThrottleBody) {
            ThrottledResponseBody(
                countingBody,
                PerformanceTuning.BackgroundThrottle.BACKGROUND_IMAGE_BYTES_PER_SECOND,
            )
        } else {
            countingBody
        }
        return response.newBuilder().body(finalBody).build()
    }
}

/**
 * Считает реально прочитанные байты тела ответа в [NetworkStats] — не заявленный `Content-Length`
 * (его может не быть у chunked-ответа, а декодирование, оборванное на середине, не должно
 * засчитываться как полный вес). Оборачивает [ForwardingSource] по тому же приёму, что и
 * [ThrottledResponseBody] ниже, но независимо от неё — эта обёртка ничего не придушивает, только
 * считает.
 */
private class CountingResponseBody(private val delegate: ResponseBody) : ResponseBody() {
    private val countingSource: BufferedSource = object : ForwardingSource(delegate.source()) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            if (read > 0) NetworkStats.addBytes(read)
            return read
        }
    }.buffer()

    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun source(): BufferedSource = countingSource
}

/**
 * Ограничивает скорость чтения тела ответа целевым [bytesPerSecond] — простой token-bucket по
 * прошедшему времени: если к этому моменту прочитано больше, чем позволяет целевая скорость,
 * поток чтения (IO-диспетчер фоновой очереди, см. `ImagePrefetcherImpl`) просто спит на разницу.
 * Не трогает обычные (не фоновые) запросы — оборачивается только вокруг помеченных
 * [BACKGROUND_FETCH_HEADER]-ом, см. [ImageCacheLifetimeInterceptor].
 */
private class ThrottledResponseBody(
    private val delegate: ResponseBody,
    private val bytesPerSecond: Long,
) : ResponseBody() {
    private var totalBytesRead = 0L
    private val startNanos = System.nanoTime()

    private val throttledSource: BufferedSource = object : ForwardingSource(delegate.source()) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            if (read <= 0) return read
            totalBytesRead += read
            val targetNanos = totalBytesRead * NANOS_PER_SECOND / bytesPerSecond
            var remainingNanos = targetNanos - (System.nanoTime() - startNanos)
            // Спим короткими срезами, а не одним долгим TimeUnit.NANOSECONDS.sleep(): большой файл
            // на 256 КБ/с легко требует многосекундной паузы за одно чтение, а один долгий sleep()
            // не реагирует на закрытие потока/отмену корутины — источник почти не отпускал ресурсы,
            // пока не проснётся сам. Срезами по [MAX_SLEEP_SLICE_NANOS] следующее чтение (и с ним
            // проверка отмены/закрытия) подхватывается заметно быстрее.
            while (remainingNanos > 0) {
                val sliceNanos = minOf(remainingNanos, MAX_SLEEP_SLICE_NANOS)
                TimeUnit.NANOSECONDS.sleep(sliceNanos)
                remainingNanos -= sliceNanos
            }
            return read
        }
    }.buffer()

    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun source(): BufferedSource = throttledSource
}

private const val HTTP_OK = 200
private const val HEADER_PRAGMA = "Pragma"
private const val HEADER_CACHE_CONTROL = "Cache-Control"

/** Отдельная от `UPDATES_DIR` (APK обновлений, см. `GitHubUpdateRepository`) поддиректория кэша. */
private const val IMAGE_DISK_CACHE_DIR = "image_cache"

private const val NANOS_PER_SECOND = 1_000_000_000L

/** Максимальный кусок паузы за одну итерацию throttle-цикла — см. doc [ThrottledResponseBody]. */
private const val MAX_SLEEP_SLICE_NANOS = 200_000_000L
