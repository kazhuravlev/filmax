package com.filmax.core.domain.cache

import com.filmax.core.domain.tuning.PerformanceTuning
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

object ImagePrefetchThrottle {
    private val origin = TimeSource.Monotonic.markNow()

    @Volatile
    private var lastActivityNanos: Long = NO_ACTIVITY_NANOS

    @Volatile
    private var playbackActive: Boolean = false

    fun touch() {
        lastActivityNanos = origin.elapsedNow().inWholeNanoseconds
    }

    fun setPlaying(isPlaying: Boolean) {
        playbackActive = isPlaying
    }

    val cooldownRemainingMillis: Long
        get() {
            val lastActivity = lastActivityNanos
            if (lastActivity == NO_ACTIVITY_NANOS) return 0L

            val elapsedNanos = origin.elapsedNow().inWholeNanoseconds - lastActivity
            val remainingNanos = COOLDOWN_NANOS - elapsedNanos
            if (remainingNanos <= 0L) return 0L
            return (remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND
        }

    val isPlaybackActive: Boolean
        get() = playbackActive

    val shouldThrottle: Boolean
        get() = playbackActive || cooldownRemainingMillis > 0L
}

private const val NO_ACTIVITY_NANOS = -1L
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val COOLDOWN_NANOS =
    PerformanceTuning.BackgroundThrottle.COOLDOWN_MS * NANOS_PER_MILLISECOND
