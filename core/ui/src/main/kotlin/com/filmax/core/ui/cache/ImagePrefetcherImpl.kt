package com.filmax.core.ui.cache

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.filmax.core.domain.cache.BackgroundFetchSettings
import com.filmax.core.domain.cache.ImageDiscovery
import com.filmax.core.domain.cache.ImagePrefetcher
import com.filmax.core.domain.cache.PrefetchImage
import com.filmax.core.domain.cache.PrefetchProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * Перед каждой закачкой явно проверяем дисковый кэш Coil по ключу ([isAlreadyCached]) и, если
 * запись уже есть, вообще не трогаем сеть — не полагаемся молча на то, что [SingletonImageLoader]
 * сам решит не ходить в сеть на свежую запись: на практике полагаться получалось не всегда
 * (одни и те же постеры перекачивались повторно). Кроме того, не поставить один и тот же ключ в
 * очередь дважды, пока первый ещё не обработан ([queuedKeys]).
 *
 * Включена/выключена — общим [BackgroundFetchSettings] (единый выключатель ВСЕЙ фоновой докачки,
 * не только картинок). Выключение не отменяет уже стоящую в очереди картинку — очередь просто
 * перестаёт забирать из неё сеть, пропуская элементы без похода в сеть (см. [processOne]), так
 * что [PrefetchProgress.remaining] всё равно корректно стекает к нулю, а не зависает.
 */
internal class ImagePrefetcherImpl(
    private val context: Context,
    private val backgroundFetch: BackgroundFetchSettings,
) : ImagePrefetcher {

    private val progressState = MutableStateFlow(PrefetchProgress())
    override val progress: StateFlow<PrefetchProgress> = progressState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<PrefetchImage>(capacity = Channel.UNLIMITED)
    private val queuedKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

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
            // add() возвращает false, если ключ уже стоит в очереди/обрабатывается — второй раз
            // тот же тайтл из другого списка (например, попал и в «Похожее», и в поиск) не дублируем.
            if (queuedKeys.add(image.key)) {
                channel.trySend(image)
                progressState.update { it.copy(remaining = queuedKeys.size) }
            }
        }
    }

    /** Пропускает картинку без похода в сеть, если фоновая загрузка выключена уже после
     * постановки в очередь — экран всё равно догрузит её сам, когда пользователь до неё дойдёт. */
    private suspend fun processOne(image: PrefetchImage) {
        if (!backgroundFetch.enabled.value) return
        runCatching { withTimeoutOrNull(PREFETCH_TIMEOUT_MS) { prefetchOne(image) } }
    }

    private suspend fun prefetchOne(image: PrefetchImage) {
        val imageLoader = SingletonImageLoader.get(context)
        if (isAlreadyCached(imageLoader, image.key)) return
        val request = ImageRequest.Builder(context)
            .data(CacheableImage(key = image.key, url = image.url))
            // Маркер для FilmaxImageLoaderFactory (app): там по нему придушивают скорость именно
            // фоновой закачки, не трогая обычные запросы — см. BACKGROUND_FETCH_HEADER. До сервера
            // заголовок не доезжает, интерцептор снимает его перед отправкой.
            .httpHeaders(NetworkHeaders.Builder().set(BACKGROUND_FETCH_HEADER, "1").build())
            .build()
        imageLoader.execute(request)
    }

    /** `openSnapshot` блокирует поток на файловом I/O, но мы и так уже на [Dispatchers.IO]
     * (см. [scope]) — отдельного переключения диспетчера не нужно. Снапшот тут же закрывается
     * ([use]): нужен только сам факт, есть ли запись, не её содержимое. */
    private fun isAlreadyCached(imageLoader: ImageLoader, key: String): Boolean =
        imageLoader.diskCache?.openSnapshot(key)?.use { true } ?: false

    private companion object {
        const val PREFETCH_TIMEOUT_MS = 15_000L
    }
}
