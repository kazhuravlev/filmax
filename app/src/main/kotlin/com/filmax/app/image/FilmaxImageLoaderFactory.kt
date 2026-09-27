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

class FilmaxImageLoaderFactory : SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .addNetworkInterceptor(ImageCacheLifetimeInterceptor())
            .build()
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
                add(Keyer<CacheableImage> { data, _ -> data.key })
                add(Mapper<CacheableImage, String> { data, _ -> data.url })
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve(IMAGE_DISK_CACHE_DIR).toOkioPath())
                    .maxSizeBytes(PerformanceTuning.ImageCache.DISK_CACHE_MAX_SIZE_BYTES)
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, PerformanceTuning.ImageCache.MEMORY_CACHE_SIZE_PERCENT)
                    .build()
            }
            .build()
    }
}

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

private const val IMAGE_DISK_CACHE_DIR = "image_cache"

private const val NANOS_PER_SECOND = 1_000_000_000L

private const val MAX_SLEEP_SLICE_NANOS = 200_000_000L
