package com.filmax.core.domain.cache

import com.filmax.core.domain.tuning.PerformanceTuning
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * Признак «прямо сейчас происходит что-то ещё» — любой обычный (не фоновый) сетевой запрос
 * (API-клиент в `core:network`, загрузка картинки для экрана, который сейчас смотрит
 * пользователь — не фоновая очередь) или активное воспроизведение в плеере. Фоновая очередь
 * картинок ([ImagePrefetcher]) приостанавливается на
 * [PerformanceTuning.BackgroundThrottle.COOLDOWN_MS] после такой активности — не соревнуется за
 * канал с тем, что реально нужно пользователю прямо сейчас. Простаивает приложение — очередь
 * работает на полной скорости.
 *
 * Источники, которые зовут [touch]/[setPlaying]: `HttpClientFactory` (core:network — любой НЕ
 * фоновый запрос основного API-клиента), `FilmaxImageLoaderFactory` (app — любая НЕ фоновая
 * загрузка картинки), `PlayerScreenModel` (feature:player:common — пока идёт воспроизведение).
 *
 * Держатель, а не Koin — сигнал нужен из мест без DI-графа (сетевой слой, интерцептор),
 * аналогично [com.filmax.core.domain.common.ErrorReporting]/[ImageDiscovery].
 */
object ImagePrefetchThrottle {
    private val origin = TimeSource.Monotonic.markNow()

    @Volatile
    private var lastActivityNanos: Long = NO_ACTIVITY_NANOS

    @Volatile
    private var playbackActive: Boolean = false

    /** Зовут источники обычной (не фоновой) сетевой активности при каждом запросе. */
    fun touch() {
        lastActivityNanos = origin.elapsedNow().inWholeNanoseconds
    }

    /** Плеер сообщает о смене состояния воспроизведения — throttle держится, пока true. */
    fun setPlaying(isPlaying: Boolean) {
        playbackActive = isPlaying
    }

    /** Остаток конечного cooldown после последнего обычного сетевого запроса, в миллисекундах. */
    val cooldownRemainingMillis: Long
        get() {
            val lastActivity = lastActivityNanos
            if (lastActivity == NO_ACTIVITY_NANOS) return 0L

            val elapsedNanos = origin.elapsedNow().inWholeNanoseconds - lastActivity
            val remainingNanos = COOLDOWN_NANOS - elapsedNanos
            if (remainingNanos <= 0L) return 0L
            return (remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND
        }

    /** true означает бессрочный throttle от активного воспроизведения, а не конечный cooldown. */
    val isPlaybackActive: Boolean
        get() = playbackActive

    /** true — фоновой закачке стоит придушить скорость: недавно была другая активность или
     * прямо сейчас идёт воспроизведение. */
    val shouldThrottle: Boolean
        get() = playbackActive || cooldownRemainingMillis > 0L
}

private const val NO_ACTIVITY_NANOS = -1L
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val COOLDOWN_NANOS =
    PerformanceTuning.BackgroundThrottle.COOLDOWN_MS * NANOS_PER_MILLISECOND
