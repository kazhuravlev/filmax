package com.filmax.core.domain.error

import com.filmax.core.domain.common.RequestResult
import kotlin.concurrent.Volatile

enum class AppError {
    Offline,

    Server,

    Timeout,

    NotFound,

    Empty,

    Premium,

    Region,

    Auth,

    Playback,
    ;

    companion object {
        fun resolve(cause: Throwable): AppError =
            ErrorClassification.classifier.classify(cause) ?: resolveHeuristically(cause.message, cause)

        fun resolveHeuristically(message: String?, cause: Throwable? = null): AppError {
            val causeName = cause?.let { it::class.simpleName.orEmpty() }.orEmpty()
            val text = buildString {
                append(message.orEmpty())
                append(' ')
                append(cause?.message.orEmpty())
            }

            return when {
                isOffline(causeName, text) -> Offline
                isTimeout(causeName, text) -> Timeout
                hasStatus(text, HttpStatus.UNAUTHORIZED) || text.contains("unauthorized", ignoreCase = true) -> Auth
                hasStatus(text, HttpStatus.PAYMENT_REQUIRED) -> Premium
                hasStatus(text, HttpStatus.FORBIDDEN) || text.contains("forbidden", ignoreCase = true) -> Region
                hasStatus(text, HttpStatus.NOT_FOUND) || text.contains("not found", ignoreCase = true) -> NotFound
                else -> Server
            }
        }

        fun fromHttpStatus(status: Int): AppError = when (status) {
            HttpStatus.UNAUTHORIZED -> Auth
            HttpStatus.PAYMENT_REQUIRED -> Premium
            HttpStatus.FORBIDDEN -> Region
            HttpStatus.NOT_FOUND -> NotFound
            HttpStatus.REQUEST_TIMEOUT -> Timeout
            else -> Server
        }

        private fun isOffline(causeName: String, text: String): Boolean =
            causeName.contains("UnknownHost") ||
                causeName.contains("ConnectException") ||
                causeName.contains("UnresolvedAddress") ||
                text.contains("Unable to resolve host", ignoreCase = true) ||
                text.contains("Failed to connect", ignoreCase = true)

        private fun isTimeout(causeName: String, text: String): Boolean =
            causeName.contains("Timeout") ||
                text.contains("timeout", ignoreCase = true) ||
                hasStatus(text, HttpStatus.REQUEST_TIMEOUT)

        private fun hasStatus(text: String, code: Int): Boolean =
            Regex("""\b$code\b""").containsMatchIn(text)
    }
}

object HttpStatus {
    const val UNAUTHORIZED = 401
    const val PAYMENT_REQUIRED = 402
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val REQUEST_TIMEOUT = 408
}

fun interface ErrorClassifier {
    fun classify(cause: Throwable): AppError?
}

object ErrorClassification {
    @Volatile
    var classifier: ErrorClassifier = ErrorClassifier { null }
}

fun RequestResult.Error.toAppError(): AppError = kind
