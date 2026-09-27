package com.filmax.core.domain.tuning

object PerformanceTuning {
    object BackgroundThrottle {
        const val COOLDOWN_MS = 10_000L

        const val THROTTLE_POLL_INTERVAL_MS = 500L

        const val BACKGROUND_IMAGE_BYTES_PER_SECOND = 256L * 1024
    }

    object ForegroundDetailsConcurrency {
        const val LIBRARY_TITLE_DETAILS = 4

        const val CONTINUATION_DETAILS = 4
    }

    object BackgroundQueues {
        const val TITLE_DETAILS_FETCH_TIMEOUT_MS = 15_000L

        const val IMAGE_PREFETCH_TIMEOUT_MS = 90_000L

        const val MAX_QUEUED_TITLE_IDS = 1000

        const val MAX_QUEUED_IMAGE_KEYS = 1000

        const val IMAGE_PREFETCH_DECODE_SIZE_PX = 32

        const val WARM_IMAGE_CONCURRENCY = 3
    }

    object NetworkClient {
        const val REQUEST_TIMEOUT_MS = 12_000L

        const val CONNECT_TIMEOUT_MS = 8_000L

        const val SOCKET_TIMEOUT_MS = 10_000L

        const val MAX_RETRIES = 2
    }

    object ImageCache {
        const val DISK_CACHE_MAX_SIZE_BYTES = 1024L * 1024 * 1024

        const val MAX_AGE_SECONDS = 30L * 24 * 60 * 60

        const val MEMORY_CACHE_SIZE_PERCENT = 0.15
    }

    object Warmup {
        const val START_DELAY_MS = 3_000L
    }
}
