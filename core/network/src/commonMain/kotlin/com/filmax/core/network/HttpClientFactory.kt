package com.filmax.core.network

import com.filmax.core.domain.cache.ImagePrefetchThrottle
import com.filmax.core.domain.cache.NetworkStats
import com.filmax.core.domain.network.ApiHostRepository
import com.filmax.core.domain.tuning.PerformanceTuning
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
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
import io.ktor.http.contentLength
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

@Suppress("TooGenericExceptionCaught", "SwallowedException")
fun buildHttpClient(
    engine: HttpClientEngine,
    tokenStorage: TokenStorage,
    hostRepository: ApiHostRepository,
    enableLogging: Boolean = false,
): HttpClient = HttpClient(engine) {
    expectSuccess = true

    installResilience()

    installActivityTracking()

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
            refreshTokens {
                val storedAccess = tokenStorage.getAccessToken()
                if (!storedAccess.isNullOrBlank() && storedAccess != oldTokens?.accessToken) {
                    return@refreshTokens BearerTokens(storedAccess, tokenStorage.getRefreshToken().orEmpty())
                }
                val refresh = tokenStorage.getRefreshToken()
                if (refresh.isNullOrBlank()) {
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
                    tokenStorage.clear()
                    null
                } catch (transient: Throwable) {
                    null
                }
            }
            sendWithoutRequest { true }
        }
    }

    if (enableLogging) {
        install(Logging) {
            logger = SecretMaskingLogger(Logger.SIMPLE)
            level = LogLevel.BODY
            sanitizeHeader { header -> header.equals(HttpHeaders.Authorization, ignoreCase = true) }
        }
    }

    defaultRequest {
        url(hostRepository.currentHost.value + "/")
    }
}

private fun HttpClientConfig<*>.installActivityTracking() {
    install(
        createClientPlugin("ActivityTrackingPlugin") {
            onRequest { request, _ ->
                if (!request.isBackgroundNetworkRequest) ImagePrefetchThrottle.touch()
            }
            onResponse { response -> NetworkStats.addBytes(response.contentLength() ?: 0) }
        },
    )
}

private fun HttpClientConfig<*>.installResilience() {
    install(HttpTimeout) {
        requestTimeoutMillis = PerformanceTuning.NetworkClient.REQUEST_TIMEOUT_MS
        connectTimeoutMillis = PerformanceTuning.NetworkClient.CONNECT_TIMEOUT_MS
        socketTimeoutMillis = PerformanceTuning.NetworkClient.SOCKET_TIMEOUT_MS
    }
    install(HttpRequestRetry) {
        maxRetries = PerformanceTuning.NetworkClient.MAX_RETRIES
        retryOnExceptionIf { request, cause -> request.method in IDEMPOTENT_METHODS && cause is IOException }
        retryIf { request, response ->
            request.method in IDEMPOTENT_METHODS && response.status.value >= HTTP_SERVER_ERROR
        }
        exponentialDelay()
    }
}

@Serializable
private data class OAuthTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Int = 0,
)

private const val HTTP_SERVER_ERROR = 500

private val IDEMPOTENT_METHODS = listOf(HttpMethod.Get, HttpMethod.Head)
