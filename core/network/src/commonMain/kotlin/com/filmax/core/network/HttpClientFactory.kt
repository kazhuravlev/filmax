package com.filmax.core.network

import com.filmax.core.domain.cache.ImagePrefetchThrottle
import com.filmax.core.domain.network.ApiHostRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

val networkJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * Общий Ktor [HttpClient]. [engine] предоставляется платформой
 * (OkHttp на Android — с Chucker-перехватчиком, Darwin на iOS).
 */
// Намеренно ловим Throwable в refreshTokens и «глотаем» его: любой транзиентный сбой обмена не
// должен ронять клиент/сессию (как и граница ошибок в safeRequest). CancellationException — выше.
@Suppress("TooGenericExceptionCaught", "SwallowedException")
fun buildHttpClient(
    engine: HttpClientEngine,
    tokenStorage: TokenStorage,
    hostRepository: ApiHostRepository,
    enableLogging: Boolean = false,
): HttpClient = HttpClient(engine) {
    expectSuccess = true

    // Без этого таймаут был бесконечным (OkHttp callTimeout не выставлен): подвисший на
    // приёме данных сервер вешал запрос навечно — пользователь видел вечный спиннер вместо
    // ошибки, по которой экран мог бы предложить Retry. requestTimeoutMillis можно смело
    // выставлять глобально: этот клиент не используется для больших закачек (обновление
    // приложения качает APK через голый HttpURLConnection в :app, см. GitHubUpdateRepository;
    // картинки идут через отдельный OkHttp в CoreUiModule/FilmaxImageLoaderFactory) — здесь
    // только короткие JSON-ответы kino.watch.
    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        socketTimeoutMillis = SOCKET_TIMEOUT_MS
    }

    // Единичный сетевой «чих» (обрыв сокета, 502/503 от балансера) не должен долетать до
    // пользователя как ошибка экрана — до maxRetries попыток он тихо повторяется сам. Ретраим
    // только идемпотентные GET/HEAD: повторный POST (история просмотра, закладки) на транзиентной
    // ошибке рискует продублировать действие, если сервер всё-таки применил первый запрос.
    // 401 сюда не попадает — retryIf смотрит только на 5xx, а обновление токена и повтор запроса
    // с новым access — забота плагина Auth выше по цепочке, задваивать эту логику здесь не нужно.
    install(HttpRequestRetry) {
        maxRetries = MAX_RETRIES
        retryOnExceptionIf { request, cause -> request.method in IDEMPOTENT_METHODS && cause is IOException }
        retryIf { request, response ->
            request.method in IDEMPOTENT_METHODS && response.status.value >= HTTP_SERVER_ERROR
        }
        exponentialDelay()
    }

    // Любой запрос основного API-клиента — это «пользователь сейчас чем-то занят» для фоновой
    // закачки картинок (см. ImagePrefetchThrottle): она придушивает себя на 10 секунд после
    // такой активности, чтобы не отъедать канал у того, что реально нужно прямо сейчас.
    install(
        createClientPlugin("ActivityTrackingPlugin") {
            onRequest { _, _ -> ImagePrefetchThrottle.touch() }
        },
    )

    install(ContentNegotiation) {
        json(networkJson)
    }

    install(Auth) {
        bearer {
            loadTokens {
                tokenStorage.getAccessToken()?.let { token ->
                    BearerTokens(token, tokenStorage.getRefreshToken().orEmpty())
                }
            }
            // Access протух → Ktor вызывает refreshTokens. Меняем refresh_token на новую пару
            // токенов через OAuth-эндпоинт и повторяем исходный запрос с новым access — без
            // форс-релогина. Запрос обновления идёт через переданный [client] (у него отключён
            // повторный Auth), поэтому рекурсии на сам refresh-эндпоинт нет.
            //
            // Особый случай — свежий вход device-flow: кэш loadTokens на старте был пуст,
            // первый запрос ушёл без заголовка и получил 401; тогда refresh_token в хранилище
            // ещё «старый», обмен даст актуальные токены (или, если его нет, — logout).
            refreshTokens {
                // Если в хранилище уже более свежий access, чем протухший (свежий device-логин —
                // кэш loadTokens на старте был пуст; либо параллельный запрос уже обновил токены) —
                // используем его без сетевого обмена (не тратим refresh_token зря).
                val storedAccess = tokenStorage.getAccessToken()
                if (!storedAccess.isNullOrBlank() && storedAccess != oldTokens?.accessToken) {
                    return@refreshTokens BearerTokens(storedAccess, tokenStorage.getRefreshToken().orEmpty())
                }
                val refresh = tokenStorage.getRefreshToken()
                if (refresh.isNullOrBlank()) {
                    // Нечем обновляться — единый сценарий logout, без цикла 401.
                    tokenStorage.clear()
                    return@refreshTokens null
                }
                try {
                    val response: OAuthTokenResponse = client.post(OAUTH_DEVICE_PATH) {
                        parameter("grant_type", "refresh_token")
                        parameter("client_id", OAUTH_CLIENT_ID)
                        parameter("client_secret", OAUTH_CLIENT_SECRET)
                        parameter("refresh_token", refresh)
                    }.body()
                    tokenStorage.save(response.accessToken, response.refreshToken)
                    BearerTokens(response.accessToken, response.refreshToken)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (rejected: ClientRequestException) {
                    // 4xx от OAuth (invalid_grant): refresh_token действительно невалиден → logout.
                    tokenStorage.clear()
                    null
                } catch (transient: Throwable) {
                    // Транзиентный сбой (offline/timeout/5xx): НЕ разлогиниваем — обновление не удалось,
                    // исходный запрос вернёт 401, но сессия сохранится до восстановления сети.
                    null
                }
            }
            sendWithoutRequest { true }
        }
    }

    if (enableLogging) {
        install(Logging) {
            // Logger.SIMPLE пишет через println — на Android это уходит в logcat (тег System.out).
            // Дефолтный логгер на JVM идёт в SLF4J, провайдера в приложении нет, и логи молча
            // терялись: в logcat было только «SLF4J(W): noProviders», а запросов — ни одного.
            logger = SecretMaskingLogger(Logger.SIMPLE)
            level = LogLevel.BODY
            // Bearer-заголовок в логи не пишем. Токены в URL режет SecretMaskingLogger:
            // sanitizeHeader на query-параметры не распространяется.
            sanitizeHeader { header -> header.equals(HttpHeaders.Authorization, ignoreCase = true) }
        }
    }

    defaultRequest {
        // Читаем hostRepository.currentHost на каждый запрос (а не один раз при сборке клиента):
        // блок defaultRequest выполняется заново для каждого исходящего запроса, поэтому смена
        // хоста (ручная или через дискавери) подхватывается без пересоздания HttpClient.
        // Хвостовой слеш — как у прежнего BASE_URL: относительные пути ("api/v1/...") иначе
        // резолвятся без последнего сегмента хоста.
        url(hostRepository.currentHost.value + "/")
    }
}

/**
 * Ответ OAuth-эндпоинта при обмене refresh_token (те же поля, что и `TokenDto` в `:data:auth`;
 * дублируется локально, чтобы сетевой слой не зависел от `:data:auth`).
 */
@Serializable
private data class OAuthTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Int = 0,
)

// requestTimeoutMillis — весь обмен запрос/ответ, включая чтение тела; connect/socket —
// отдельно фаза установления соединения и пауза между пакетами при чтении. Втроём режут
// три разных сценария зависания, которые OkHttp без HttpTimeout не ловил вовсе.
private const val REQUEST_TIMEOUT_MS = 12_000L
private const val CONNECT_TIMEOUT_MS = 8_000L
private const val SOCKET_TIMEOUT_MS = 10_000L

private const val MAX_RETRIES = 2
private const val HTTP_SERVER_ERROR = 500

/** GET/HEAD безопасно повторять — они не меняют состояние на сервере. */
private val IDEMPOTENT_METHODS = listOf(HttpMethod.Get, HttpMethod.Head)
