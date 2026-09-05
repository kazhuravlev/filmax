package com.filmax.core.domain.common

import kotlin.concurrent.Volatile

/**
 * Точка расширения для сетевого слоя: при сбое соединения (offline/timeout, см. [AppError])
 * даёт шанс попробовать другой хост API при следующем запросе. Контракт живёт в domain, чтобы
 * [safeRequest] мог дёрнуть его без зависимости от core:network — реализацию (перезапуск
 * дискавери хоста) подставляет `core:network` при создании `ApiHostRepositoryImpl`, аналогично
 * тому, как [ErrorReporting] подставляет репортер телеметрии.
 */
fun interface ConnectionFailureHandler {
    fun onConnectionFailure()
}

object ConnectionFailures {

    @Volatile
    var handler: ConnectionFailureHandler = ConnectionFailureHandler {}
}
