package com.filmax.core.domain.common

import kotlin.concurrent.Volatile

fun interface ConnectionFailureHandler {
    fun onConnectionFailure()
}

object ConnectionFailures {
    @Volatile
    var handler: ConnectionFailureHandler = ConnectionFailureHandler {}
}
