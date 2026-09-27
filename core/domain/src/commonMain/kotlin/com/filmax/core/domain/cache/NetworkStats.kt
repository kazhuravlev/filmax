package com.filmax.core.domain.cache

import kotlin.concurrent.Volatile

object NetworkStats {
    @Volatile
    private var totalBytesState: Long = 0L

    val totalBytes: Long
        get() = totalBytesState

    fun addBytes(bytes: Long) {
        if (bytes <= 0) return
        totalBytesState += bytes
    }
}
