package com.filmax.core.domain.error

import com.filmax.core.domain.common.RequestResult
import kotlin.concurrent.Volatile

/**
 * Семантический тип ошибки приложения. Граница ошибок data-слоя ([com.filmax.core.domain.common.safeRequest])
 * резолвит «сырое» исключение в [AppError] РОВНО ОДИН РАЗ и кладёт его в [RequestResult.Error.kind];
 * презентационный слой дальше работает только с типом, а не с текстом сообщения. UI-слой маппит
 * [AppError] на иконку/цвет/тексты.
 */
enum class AppError {
    Offline,

    /** Сбой на стороне сервера (5xx). */
    Server,

    /** Сервер не ответил вовремя (408 / timeout). */
    Timeout,

    /** Контент не найден (404). */
    NotFound,

    /** Пустой результат (запрос успешен, но данных нет). */
    Empty,

    /** Требуется подписка Premium (402). */
    Premium,

    /** Контент недоступен в регионе (403). */
    Region,

    /** Сессия истекла / не авторизован (401). */
    Auth,

    Playback,
    ;

    companion object {
        /**
         * Резолвит исключение в [AppError]. Сначала — типизированный классификатор платформы
         * ([ErrorClassification.classifier]: сетевой слой знает реальные классы исключений и HTTP-статус
         * ответа), и только для исключений, которых он не знает (падение парсинга, произвольный
         * Throwable из репозитория), — текстовая эвристика [resolveHeuristically].
         */
        fun resolve(cause: Throwable): AppError =
            ErrorClassification.classifier.classify(cause) ?: resolveHeuristically(cause.message, cause)

        /**
         * Запасная эвристика по имени класса и тексту (без зависимостей от Ktor/Android). Публична
         * только ради тестов и как fallback [resolve]: новый код обязан идти через [resolve].
         */
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

        /**
         * Тип ошибки по HTTP-статусу ответа — единственная таблица «код → [AppError]» в приложении;
         * сетевой классификатор ([ErrorClassification]) зовёт её с настоящим статусом ответа.
         */
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

        /** Сервер не ответил вовремя (timeout / 408). */
        private fun isTimeout(causeName: String, text: String): Boolean =
            causeName.contains("Timeout") ||
                text.contains("timeout", ignoreCase = true) ||
                hasStatus(text, HttpStatus.REQUEST_TIMEOUT)

        private fun hasStatus(text: String, code: Int): Boolean =
            Regex("""\b$code\b""").containsMatchIn(text)
    }
}

/** HTTP-статусы, у которых есть свой [AppError]; остальные 4xx/5xx — [AppError.Server]. */
object HttpStatus {
    const val UNAUTHORIZED = 401
    const val PAYMENT_REQUIRED = 402
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val REQUEST_TIMEOUT = 408
}

/**
 * Типизированный классификатор исключений. Реализацию подставляет сетевой слой (он знает классы
 * исключений Ktor и HTTP-статус ответа); домен без него живёт на текстовой эвристике. null —
 * «это исключение мне незнакомо», тогда [AppError.resolve] падает на эвристику.
 */
fun interface ErrorClassifier {
    fun classify(cause: Throwable): AppError?
}

/**
 * Держатель классификатора — по образцу [com.filmax.core.domain.common.ErrorReporting]:
 * `safeRequest` — inline-функция верхнего уровня без DI.
 */
object ErrorClassification {
    @Volatile
    var classifier: ErrorClassifier = ErrorClassifier { null }
}

/** Тип сбоя результата — разрешён один раз в `safeRequest`, повторно ничего не парсится. */
fun RequestResult.Error.toAppError(): AppError = kind
