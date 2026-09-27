package com.filmax.core.ui.cache

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import com.filmax.core.domain.cache.BackgroundFetchSettings
import com.filmax.core.domain.cache.ImageDiscovery
import com.filmax.core.domain.cache.ImagePrefetchThrottle
import com.filmax.core.domain.cache.ImagePrefetcher
import com.filmax.core.domain.cache.PrefetchImage
import com.filmax.core.domain.cache.PrefetchProgress
import com.filmax.core.domain.tuning.PerformanceTuning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal class ImagePrefetcherImpl(
    private val context: Context,
    private val backgroundFetch: BackgroundFetchSettings,
) : ImagePrefetcher {
    private val progressState = MutableStateFlow(PrefetchProgress())
    override val progress: StateFlow<PrefetchProgress> = progressState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<PrefetchImage>(capacity = Channel.UNLIMITED)
    private val queuedKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val warmSlots = Semaphore(PerformanceTuning.BackgroundQueues.WARM_IMAGE_CONCURRENCY)
    private val warmingKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    init {
        ImageDiscovery.prefetcher = this
        scope.launch {
            for (image in channel) {
                processOne(image)
                queuedKeys.remove(image.key)
                progressState.update {
                    it.copy(downloaded = it.downloaded + 1, remaining = queuedKeys.size)
                }
            }
        }
    }

    override fun enqueue(images: List<PrefetchImage>) {
        if (!backgroundFetch.enabled.value) return
        for (image in images) {
            if (queuedKeys.size >= PerformanceTuning.BackgroundQueues.MAX_QUEUED_IMAGE_KEYS) continue
            if (queuedKeys.add(image.key)) {
                channel.trySend(image)
                progressState.update { it.copy(remaining = queuedKeys.size) }
            }
        }
    }

    override fun warm(images: List<PrefetchImage>) {
        for (image in images) {
            if (!warmingKeys.add(image.key)) continue
            scope.launch {
                try {
                    warmSlots.withPermit {
                        runCatching {
                            withTimeoutOrNull(PerformanceTuning.BackgroundQueues.IMAGE_PREFETCH_TIMEOUT_MS) {
                                prefetchOne(image, background = false)
                            }
                        }
                    }
                } finally {
                    warmingKeys.remove(image.key)
                }
            }
        }
    }

    private suspend fun processOne(image: PrefetchImage) {
        if (!backgroundFetch.enabled.value) return
        while (ImagePrefetchThrottle.shouldThrottle) {
            delay(PerformanceTuning.BackgroundThrottle.THROTTLE_POLL_INTERVAL_MS)
        }
        runCatching {
            withTimeoutOrNull(PerformanceTuning.BackgroundQueues.IMAGE_PREFETCH_TIMEOUT_MS) {
                prefetchOne(image)
            }
        }
    }

    private suspend fun prefetchOne(image: PrefetchImage, background: Boolean = true) {
        val imageLoader = SingletonImageLoader.get(context)
        if (isAlreadyCached(imageLoader, image.key)) return
        val request = ImageRequest.Builder(context)
            .data(CacheableImage(key = image.key, url = image.url))
            .apply {
                if (background) httpHeaders(NetworkHeaders.Builder().set(BACKGROUND_FETCH_HEADER, "1").build())
            }
            .memoryCachePolicy(CachePolicy.DISABLED)
            .size(PerformanceTuning.BackgroundQueues.IMAGE_PREFETCH_DECODE_SIZE_PX)
            .precision(Precision.INEXACT)
            .build()
        imageLoader.execute(request)
    }

    private fun isAlreadyCached(imageLoader: ImageLoader, key: String): Boolean =
        imageLoader.diskCache?.openSnapshot(key)?.use { true } ?: false
}
