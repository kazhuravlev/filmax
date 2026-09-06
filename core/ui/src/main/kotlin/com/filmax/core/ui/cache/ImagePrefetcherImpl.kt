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
 *
 * Очередь ограничена [MAX_QUEUED_KEYS] элементами (drop-newest): при переполнении новый элемент
 * не попадает ни в [queuedKeys], ни в [channel] — иначе экран со списком из тысяч постеров разом
 * забивает [Channel.UNLIMITED] и [queuedKeys] безграничным множеством ключей, которое никогда не
 * догоняется. Дропаем именно новые, а не старые — иначе пришлось бы вычищать уже отправленный в
 * канал элемент, а `Channel` этого не умеет.
 *
 * Перед КАЖДЫМ элементом (см. [processOne]) ждём, пока [ImagePrefetchThrottle.shouldThrottle] не
 * станет false — раньше придушивалась только скорость самой закачки (см. `ThrottledResponseBody`
 * в `FilmaxImageLoaderFactory`), а декодирование картинки всё равно шло сразу и соревновалось с
 * UI-потоком за CPU/память во время скролла. Теперь фоновая очередь не трогает CPU вовсе, пока
 * пользователь активен.
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
            // Переполнение — дропаем новый элемент, не трогая ни очередь, ни queuedKeys (см. doc
            // класса): ключ не должен остаться в множестве, иначе он никогда не будет обработан
            // и не освободит место для будущих элементов.
            if (queuedKeys.size >= MAX_QUEUED_KEYS) continue
            // add() возвращает false, если ключ уже стоит в очереди/обрабатывается — второй раз
            // тот же тайтл из другого списка (например, попал и в «Похожее», и в поиск) не дублируем.
            if (queuedKeys.add(image.key)) {
                channel.trySend(image)
                progressState.update { it.copy(remaining = queuedKeys.size) }
            }
        }
    }

    /** Пропускает картинку без похода в сеть, если фоновая загрузка выключена уже после
     * постановки в очередь — экран всё равно догрузит её сам, когда пользователь до неё дойдёт.
     * Перед обработкой ждём, пока пользователь неактивен (см. doc класса) — иначе декодирование
     * прогрева соревнуется с UI-потоком прямо во время скролла/воспроизведения. */
    private suspend fun processOne(image: PrefetchImage) {
        if (!backgroundFetch.enabled.value) return
        while (ImagePrefetchThrottle.shouldThrottle) delay(THROTTLE_POLL_DELAY_MS)
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
            // Прогрев должен наполнить только ДИСКОВЫЙ кэш — декодированный битмап тут же
            // выбрасывается, класть его в память некуда и незачем. Раньше запрос без .size()
            // декодировался в полное разрешение и оседал в memory cache Coil ПОД ТЕМ ЖЕ ключом,
            // что и обычная загрузка на экране (см. Keyer<CacheableImage> в
            // FilmaxImageLoaderFactory) — несколько таких прогревов подряд вымывали LRU большими
            // битмапами. INEXACT + маленький размер — декодер может даунсэмплить не глядя на
            // реальные пропорции, результат всё равно не используется.
            .memoryCachePolicy(CachePolicy.DISABLED)
            .size(PREFETCH_DECODE_SIZE_PX)
            .precision(Precision.INEXACT)
            .build()
        imageLoader.execute(request)
    }

    /** `openSnapshot` блокирует поток на файловом I/O, но мы и так уже на [Dispatchers.IO]
     * (см. [scope]) — отдельного переключения диспетчера не нужно. Снапшот тут же закрывается
     * ([use]): нужен только сам факт, есть ли запись, не её содержимое. */
    private fun isAlreadyCached(imageLoader: ImageLoader, key: String): Boolean =
        imageLoader.diskCache?.openSnapshot(key)?.use { true } ?: false

    private companion object {
        // 90 c, не 15 — фоновая закачка сама себя придушивает до ~256 КБ/с (см.
        // FilmaxImageLoaderFactory.BACKGROUND_FETCH_BYTES_PER_SECOND), а бэкдропы/кадры легко
        // весят 2-3 МБ: на такой скорости это легитимно дольше 15 c, и работа бросалась
        // на середине скачивания, так и не догрузившись.
        const val PREFETCH_TIMEOUT_MS = 90_000L

        // Результат декодирования отбрасывается (memoryCachePolicy = DISABLED выше) — размер
        // нужен только чтобы Coil не декодировал прогрев в полное разрешение.
        const val PREFETCH_DECODE_SIZE_PX = 32

        /** Максимум одновременно стоящих в очереди картинок — см. doc класса. Поднят с 500 до
         * 1000 вместе с тем же лимитом у `TitleBackgroundFetcherImpl` (data:catalog): фоновая
         * докачка тайтлов теперь заявляет постер КАЖДОГО тайтла КАЖДОГО списка, а не только
         * тех, что дошли до полных деталей — очередь картинок стала наполняться заметно плотнее. */
        const val MAX_QUEUED_KEYS = 1000

        const val THROTTLE_POLL_DELAY_MS = 500L
    }
}
