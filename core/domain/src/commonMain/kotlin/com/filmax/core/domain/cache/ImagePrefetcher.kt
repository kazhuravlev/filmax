package com.filmax.core.domain.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

data class PrefetchImage(val key: String, val url: String)

data class PrefetchProgress(val downloaded: Int = 0, val remaining: Int = 0)

interface ImagePrefetcher {
    val progress: StateFlow<PrefetchProgress>

    fun enqueue(images: List<PrefetchImage>)

    fun warm(images: List<PrefetchImage>) = enqueue(images)
}

object ImageDiscovery {
    @Volatile
    var prefetcher: ImagePrefetcher = NoopImagePrefetcher

    fun discovered(images: List<PrefetchImage>) {
        if (images.isNotEmpty()) prefetcher.enqueue(images)
    }

    fun warm(images: List<PrefetchImage>) {
        if (images.isNotEmpty()) prefetcher.warm(images)
    }
}

private object NoopImagePrefetcher : ImagePrefetcher {
    override val progress: StateFlow<PrefetchProgress> = MutableStateFlow(PrefetchProgress())
    override fun enqueue(images: List<PrefetchImage>) = Unit
}
