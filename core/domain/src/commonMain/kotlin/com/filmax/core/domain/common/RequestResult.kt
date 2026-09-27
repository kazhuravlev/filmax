package com.filmax.core.domain.common

import com.filmax.core.domain.error.AppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface RequestResult<out T> {
    data class Success<out T>(val data: T) : RequestResult<T>

    data class Error(
        val kind: AppError,
        val message: String? = null,
        val cause: Throwable? = null,
    ) : RequestResult<Nothing> {
        companion object {
            fun of(cause: Throwable): Error = Error(AppError.resolve(cause), cause.message, cause)
        }
    }
}

@Suppress("TooGenericExceptionCaught")
suspend inline fun <T> safeRequest(crossinline block: suspend () -> T): RequestResult<T> =
    try {
        RequestResult.Success(withContext(Dispatchers.IO) { block() })
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        val failure = RequestResult.Error.of(error)
        ErrorReporting.reporter.reportRequestFailure(failure.kind, error)
        if (failure.kind == AppError.Offline || failure.kind == AppError.Timeout) {
            ConnectionFailures.handler.onConnectionFailure()
        }
        failure
    }

inline fun <T, R> RequestResult<T>.map(transform: (T) -> R): RequestResult<R> = when (this) {
    is RequestResult.Success -> RequestResult.Success(transform(data))
    is RequestResult.Error -> this
}

inline fun <T> RequestResult<T>.onSuccess(block: (T) -> Unit): RequestResult<T> {
    if (this is RequestResult.Success) block(data)
    return this
}

inline fun <T> RequestResult<T>.onError(block: (RequestResult.Error) -> Unit): RequestResult<T> {
    if (this is RequestResult.Error) block(this)
    return this
}

fun <T> RequestResult<T>.getOrNull(): T? = (this as? RequestResult.Success)?.data

fun <T> RequestResult<T>.errorOrNull(): RequestResult.Error? = this as? RequestResult.Error

fun firstError(vararg results: RequestResult<*>): RequestResult.Error? =
    results.firstNotNullOfOrNull { it.errorOrNull() }

fun firstErrorMessage(vararg results: RequestResult<*>): String? = firstError(*results)?.message
