package com.filmax.core.domain.cache

import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

/**
 * Кэш изображений (постеры, фоны, фото актёров) — единая точка сброса для настроек.
 * Хранение и срок жизни записей — забота реализации (см. `core:ui`, где живёт загрузчик картинок).
 *
 * [stats] — сколько файлов и байт сейчас лежит в кэше, для подписи на кнопке сброса; см.
 * [ImageCacheStats] о том, откуда эти цифры берутся.
 */
interface ImageCacheRepository {
    val stats: StateFlow<ImageCacheStats>

    suspend fun clear()
}

/**
 * Компактная статистика дискового кэша изображений. Копится инкрементально при каждой реальной
 * закачке (см. [ImageCacheStatsRecorder]), а не сканированием диска на каждый показ настроек, и
 * обнуляется явным сбросом кэша ([ImageCacheRepository.clear]).
 */
data class ImageCacheStats(val fileCount: Int = 0, val totalBytes: Long = 0L)

/** Один факт: столько-то байт только что реально ушло в дисковый кэш изображений. */
fun interface ImageCacheStatsRecorder {
    fun recordCached(bytes: Long)
}

/**
 * Точка учёта вне DI-графа: OkHttp network-interceptor в `app`-модуле видит момент реальной сетевой
 * закачки картинки (а не сама пишет файл на диск — это дело Coil), но сам не участвует в
 * Koin-графе — поэтому шлёт сюда, аналогично [ImageDiscovery]/[com.filmax.core.domain.common.ErrorReporting].
 * Реализацию (персистентный счётчик) подставляет `core:ui` при старте, до этого — no-op.
 */
object ImageCacheStatsRecording {
    @Volatile
    var recorder: ImageCacheStatsRecorder = ImageCacheStatsRecorder {}
}
