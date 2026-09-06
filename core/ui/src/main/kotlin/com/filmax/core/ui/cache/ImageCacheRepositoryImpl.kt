package com.filmax.core.ui.cache

import android.content.Context
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import com.filmax.core.domain.cache.ImageCacheRepository
import com.filmax.core.domain.cache.ImageCacheStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Чистит и памятный, и дисковый кэш общего Coil-загрузчика (см. `FilmaxImageLoaderFactory` в
 * `app`), а заодно читает его текущий размер — для подписи на кнопке сброса в настройках.
 *
 * [stats] — живое чтение `coil3.disk.DiskCache.size`/`.maxSize`, а не счётчик по фактам закачки:
 * Coil сам ведёт эти два числа в памяти (никакого сканирования диска на каждый показ настроек),
 * и, в отличие от инкрементального счётчика, они не расходятся с реальностью, когда Coil тихо
 * вытесняет старые записи по лимиту размера. Опрашиваем раз в [STATS_REFRESH_INTERVAL_MS] — дешёвое
 * чтение поля, а не I/O — пока кто-то подписан (обычно только открытый экран настроек).
 *
 * Первое чтение [readStats] нарочно не в конструкторе: `diskCacheProvider()` дергает
 * `SingletonImageLoader.get(context)`, который на первом обращении собирает весь `ImageLoader`
 * и синхронно инициализирует журнал дискового кэша Coil (`DiskLruCache`) — секунды на «грязном»
 * журнале объёмом до 1 ГБ после обновления приложения. Этот репозиторий создаётся `createdAtStart`
 * внутри `startKoin` в `Application.onCreate`, поэтому такой вызов в конструкторе блокировал бы
 * главный поток и первый кадр. Вместо этого стартовое значение — пустая [ImageCacheStats], а
 * реальное чтение уезжает в уже существующий `scope.launch` (на `Dispatchers.IO`, до цикла delay).
 */
internal class ImageCacheRepositoryImpl(
    private val context: Context,
    // Тестовые швы: по умолчанию — ровно текущее боевое поведение (SingletonImageLoader.get(context)).
    // ImageCacheRepositoryImplTest подставляет сюда фейковый DiskCache/лямбду очистки, не трогая
    // production-вызов CoreUiModule.kt (ImageCacheRepositoryImpl(androidContext())).
    private val diskCacheProvider: () -> DiskCache? = { SingletonImageLoader.get(context).diskCache },
    private val clearCaches: () -> Unit = {
        val imageLoader = SingletonImageLoader.get(context)
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    },
) : ImageCacheRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statsState = MutableStateFlow(ImageCacheStats())

    override val stats: StateFlow<ImageCacheStats> = statsState.asStateFlow()

    init {
        scope.launch {
            statsState.value = readStats()
            while (isActive) {
                delay(STATS_REFRESH_INTERVAL_MS)
                statsState.value = readStats()
            }
        }
    }

    override suspend fun clear() {
        clearCaches()
        statsState.value = readStats()
    }

    private fun readStats(): ImageCacheStats {
        val diskCache = diskCacheProvider() ?: return ImageCacheStats()
        return ImageCacheStats(sizeBytes = diskCache.size, maxSizeBytes = diskCache.maxSize)
    }

    private companion object {
        const val STATS_REFRESH_INTERVAL_MS = 3_000L
    }
}
