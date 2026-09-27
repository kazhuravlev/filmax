package com.filmax.core.domain.common

import com.filmax.core.domain.error.AppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Унифицированный результат запроса к данным.
 * Репозиторий ловит ошибку через [safeRequest] и отдаёт в presentation уже готовый результат —
 * никаких try/catch в ViewModel.
 */
sealed interface RequestResult<out T> {
    data class Success<out T>(val data: T) : RequestResult<T>

    /**
     * [kind] — семантический тип сбоя, разрешённый один раз на границе ошибок; [message] — только
     * для логов и подписей, по нему ничего не ветвится (см. [AppError.resolve]).
     */
    data class Error(
        val kind: AppError,
        val message: String? = null,
        val cause: Throwable? = null,
    ) : RequestResult<Nothing> {
        companion object {
            /** Сбой из исключения: тип резолвится классификатором, текст — из самого исключения. */
            fun of(cause: Throwable): Error = Error(AppError.resolve(cause), cause.message, cause)
        }
    }
}

/** Выполняет [block], оборачивая исключения в [RequestResult.Error]. CancellationException пробрасывается. */
// Намеренная граница ошибок: любой сбой запроса конвертируется в RequestResult.Error (кроме отмены).
//
// [block] выполняется на Dispatchers.IO — единая точка для всего data-слоя, а не правка каждого
// репозитория по отдельности: почти все repository-функции — это suspend-обёртки без своего
// dispatcher вокруг safeRequest { api.getX().toDomain() }, а каждый ScreenModel по умолчанию
// стартует на Dispatchers.Main.immediate (BaseScreenModel.screenModelScope). Без этого сетевой
// await, декодирование JSON в .body<T>() и маппинг DTO→domain внутри [block] шли прямо на главном
// потоке — конкурируя с Compose за кадр на слабых TV-приставках. Возвращаться на Main вручную
// после [block] не нужно: withContext сам восстанавливает исходный диспетчер вызывающей
// suspend-функции, а BaseScreenModel.updateState() дополнительно и независимо переключается на
// Main сам перед записью в state — так что даже если бы этот switch отсутствовал, состояние всё
// равно обновилось бы на правильном потоке.
@Suppress("TooGenericExceptionCaught")
suspend inline fun <T> safeRequest(crossinline block: suspend () -> T): RequestResult<T> =
    try {
        RequestResult.Success(withContext(Dispatchers.IO) { block() })
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        // Единственная точка, где видны ВСЕ сбои data-слоя: HTTP-статусы (expectSuccess=true даёт
        // исключение с URL и кодом, включая 500-е) и падения парсинга. Что из этого поедет в
        // телеметрию событием, а что крошкой, решает reportRequestFailure.
        val failure = RequestResult.Error.of(error)
        ErrorReporting.reporter.reportRequestFailure(failure.kind, error)
        // Offline/Timeout — похоже, что не сервер лежит, а недоступен конкретный хост (блокировка
        // провайдером и т.п.). Даём сети шанс переключиться на другой хост при следующем запросе.
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

/** Первый сбой среди результатов, либо null если все успешны. */
fun firstError(vararg results: RequestResult<*>): RequestResult.Error? =
    results.firstNotNullOfOrNull { it.errorOrNull() }

/** Первое сообщение об ошибке среди результатов, либо null если все успешны. Только для подписей. */
fun firstErrorMessage(vararg results: RequestResult<*>): String? = firstError(*results)?.message
