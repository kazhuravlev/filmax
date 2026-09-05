package com.filmax.core.ui.cache

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.filmax.core.domain.cache.ImageDiscovery
import com.filmax.core.domain.cache.ImagePrefetcher
import com.filmax.core.domain.cache.PrefetchImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Тихая фоновая закачка обнаруженных картинок (постеры/фото актёров) в кэш Coil — раньше, чем
 * пользователь реально откроет экран с ними (см. [ImageDiscovery]).
 *
 * Собственный [CoroutineScope], не завязанный ни на один экран: очередь переживает навигацию и
 * продолжает работать, даже когда открыт плеер — закачка не мешает воспроизведению, потому что
 * идёт СТРОГО последовательно (одна картинка за раз, `for` по [Channel] в одной корутине), а не
 * параллельным залпом, который отъедал бы соединения у активного контента.
 *
 * Повторной сети на уже закэшированное не будет: [ImageRequest] выполняется через обычный
 * [SingletonImageLoader], а тот сам проверяет память/диск раньше похода в сеть — здесь достаточно
 * не поставить один и тот же ключ в очередь дважды, пока первый ещё не обработан ([queuedKeys]).
 */
internal class ImagePrefetcherImpl(private val context: Context) : ImagePrefetcher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<PrefetchImage>(capacity = Channel.UNLIMITED)
    private val queuedKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    init {
        ImageDiscovery.prefetcher = this
        scope.launch {
            for (image in channel) {
                runCatching { withTimeoutOrNull(PREFETCH_TIMEOUT_MS) { prefetchOne(image) } }
                queuedKeys.remove(image.key)
            }
        }
    }

    override fun enqueue(images: List<PrefetchImage>) {
        for (image in images) {
            // add() возвращает false, если ключ уже стоит в очереди/обрабатывается — второй раз
            // тот же тайтл из другого списка (например, попал и в «Похожее», и в поиск) не дублируем.
            if (queuedKeys.add(image.key)) {
                channel.trySend(image)
            }
        }
    }

    private suspend fun prefetchOne(image: PrefetchImage) {
        val imageLoader = SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context)
            .data(CacheableImage(key = image.key, url = image.url))
            .build()
        imageLoader.execute(request)
    }

    private companion object {
        const val PREFETCH_TIMEOUT_MS = 15_000L
    }
}
