package com.filmax.core.domain.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

interface TitleBackgroundFetcher {
    val progress: StateFlow<PrefetchProgress>

    fun enqueue(items: List<DiscoveredTitle>)
}

data class DiscoveredTitle(
    val id: Int,
    val previewJson: String? = null,
)

object ItemDiscovery {
    @Volatile
    var prefetcher: TitleBackgroundFetcher = NoopTitleBackgroundFetcher

    fun discovered(itemId: Int) {
        prefetcher.enqueue(listOf(DiscoveredTitle(itemId)))
    }

    fun discovered(itemIds: List<Int>) {
        if (itemIds.isNotEmpty()) prefetcher.enqueue(itemIds.map(::DiscoveredTitle))
    }

    fun discovered(item: DiscoveredTitle) {
        prefetcher.enqueue(listOf(item))
    }
}

private object NoopTitleBackgroundFetcher : TitleBackgroundFetcher {
    override val progress: StateFlow<PrefetchProgress> = MutableStateFlow(PrefetchProgress())
    override fun enqueue(items: List<DiscoveredTitle>) = Unit
}
