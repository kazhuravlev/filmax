package com.filmax.core.domain.common

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Лёгкий in-memory кэш последнего успешного значения — основа офлайн-устойчивости (issue #42).
 * Регистрируется как `single` в DI, поэтому переживает пересоздание use-case/ScreenModel и
 * позволяет отдать ранее загруженный контент, когда сеть недоступна.
 *
 * Хранение только в памяти (сбрасывается при перезапуске процесса) — этого достаточно для
 * graceful degradation в рамках сессии; при необходимости персистентность добавляется отдельно.
 */
class LastValueCache<T : Any> {
    private val mutex = Mutex()
    private var cached: T? = null

    suspend fun get(): T? = mutex.withLock { cached }

    suspend fun put(value: T) = mutex.withLock { cached = value }

    /**
     * Пишет значение, только если кэш ещё пуст — атомарно под тем же [mutex], что и [put]/[get].
     * Нужен фоновому прогреву (`AppWarmup`): экран — единственный источник истины для этого кэша,
     * и прогрев обязан лишь подложить данные под пустой экран, а не перезаписать то, что экран уже
     * успел сам туда положить (иначе можно словить рассинхрон между показанным и закэшированным).
     */
    suspend fun putIfAbsent(value: T) = mutex.withLock { if (cached == null) cached = value }
}
