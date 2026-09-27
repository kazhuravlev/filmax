package com.filmax.core.domain.common

import com.filmax.core.domain.error.AppError
import com.filmax.core.domain.error.RequestFailure
import kotlin.concurrent.Volatile

interface ErrorReporter {
    fun setUser(id: String?)

    fun log(message: String)

    fun report(error: Throwable)
}

object ErrorReporting {
    @Volatile
    var reporter: ErrorReporter = NoopErrorReporter

    private object NoopErrorReporter : ErrorReporter {
        override fun setUser(id: String?) = Unit
        override fun log(message: String) = Unit
        override fun report(error: Throwable) = Unit
    }
}

fun ErrorReporter.reportRequestFailure(kind: AppError, error: Throwable) {
    if (kind in REPORTED_ERRORS) {
        report(RequestFailure.of(kind, error))
    } else {
        log("request failed (${kind.name}): ${error::class.simpleName}: ${error.message}")
    }
}

private val REPORTED_ERRORS = setOf(AppError.Server, AppError.Empty, AppError.Playback)
