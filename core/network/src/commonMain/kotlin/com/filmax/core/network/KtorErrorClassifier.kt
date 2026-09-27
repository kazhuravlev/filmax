package com.filmax.core.network

import com.filmax.core.domain.error.AppError
import com.filmax.core.domain.error.ErrorClassifier
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.util.network.UnresolvedAddressException

/**
 * Типизированная классификация сбоев Ktor — по классу исключения и HTTP-статусу ответа, а не по
 * тексту сообщения. Подставляется в [com.filmax.core.domain.error.ErrorClassification] на старте
 * приложения. Незнакомые исключения (падение парсинга, произвольный Throwable) — null, и домен
 * доклассифицирует их своей эвристикой.
 */
object KtorErrorClassifier : ErrorClassifier {
    override fun classify(cause: Throwable): AppError? = when (cause) {
        is ResponseException -> AppError.fromHttpStatus(cause.response.status.value)
        is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException -> AppError.Timeout
        is UnresolvedAddressException -> AppError.Offline
        else -> null
    }
}
