package com.filmax.core.domain.common

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LastValueCache<T : Any> {
    private val mutex = Mutex()
    private var cached: T? = null

    suspend fun get(): T? = mutex.withLock { cached }

    suspend fun put(value: T) = mutex.withLock { cached = value }

    suspend fun putIfAbsent(value: T) = mutex.withLock { if (cached == null) cached = value }
}
