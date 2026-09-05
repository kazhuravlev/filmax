package com.filmax.core.domain.cache

import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Признак «прямо сейчас происходит что-то ещё» — любой обычный (не фоновый) сетевой запрос
 * (API-клиент в `core:network`, загрузка картинки для экрана, который сейчас смотрит
 * пользователь — не фоновая очередь) или активное воспроизведение в плеере. Фоновая закачка
 * картинок ([ImagePrefetcher]) снижает себе скорость на [THROTTLE_COOLDOWN] после такой
 * активности — не соревнуется за канал с тем, что реально нужно пользователю прямо сейчас.
 * Простаивает приложение — очередь работает на полной скорости.
 *
 * Источники, которые зовут [touch]/[setPlaying]: `HttpClientFactory` (core:network — любой
 * запрос основного API-клиента), `FilmaxImageLoaderFactory` (app — любая НЕ фоновая загрузка
 * картинки), `PlayerScreenModel` (feature:player:common — пока идёт воспроизведение).
 *
 * Держатель, а не Koin — сигнал нужен из мест без DI-графа (сетевой слой, интерцептор),
 * аналогично [com.filmax.core.domain.common.ErrorReporting]/[ImageDiscovery].
 */
object ImagePrefetchThrottle {
    private val origin = TimeSource.Monotonic.markNow()

    @Volatile
    private var lastActivityNanos: Long = 0L

    @Volatile
    private var playing: Boolean = false

    /** Зовут источники обычной (не фоновой) сетевой активности при каждом запросе. */
    fun touch() {
        lastActivityNanos = origin.elapsedNow().inWholeNanoseconds
    }

    /** Плеер сообщает о смене состояния воспроизведения — throttle держится, пока true. */
    fun setPlaying(isPlaying: Boolean) {
        playing = isPlaying
    }

    /** true — фоновой закачке стоит придушить скорость: недавно была другая активность или
     * прямо сейчас идёт воспроизведение. */
    val shouldThrottle: Boolean
        get() = playing || (origin.elapsedNow().inWholeNanoseconds - lastActivityNanos).nanoseconds < THROTTLE_COOLDOWN
}

private val THROTTLE_COOLDOWN = 10.seconds
