package com.filmax.core.network

import com.filmax.core.domain.common.ConnectionFailureHandler
import com.filmax.core.domain.common.ConnectionFailures
import com.filmax.core.domain.network.ApiHostRepository
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Хосты-кандидаты API kino.watch, в порядке предпочтения. Первый — текущий продовый хост,
 * остальные — зеркала того же бэкенда (см. `doccs-api/API_CONTRACT.md` §1: у эталонного
 * веб-клиента kino.pub ровно такой же список для health-check/дискавери).
 */
const val PRIMARY_API_HOST = "https://smarttvcdn.online"

val API_HOSTS = listOf(
    PRIMARY_API_HOST,
    "https://api.boramoraboom.ru",
    "https://api.srvkp.com",
)

private const val KEY_API_HOST = "api_host"
private const val KEY_API_HOST_PREFERENCE_VERSION = "api_host_preference_version"
private const val API_HOST_PREFERENCE_VERSION = 2
private const val DISCOVERY_TIMEOUT_MS = 5_000L

/** Лёгкий endpoint: совместимый API без токена отвечает на него HTTP 401. */
private const val PROBE_PATH = "api/v1/countries"

/**
 * Текущий хост API + дискавери рабочего хоста среди [API_HOSTS].
 *
 * После миграции начинает со smarttvcdn; автоматический failover не переживает перезапуск,
 * поэтому временный сбой не закрепляет зеркало навсегда. Дискавери запускается реактивно:
 * подписывается на [ConnectionFailures] и перебирает [API_HOSTS], когда
 * [safeRequest][com.filmax.core.domain.common.safeRequest] встречает Offline/Timeout.
 */
class ApiHostRepositoryImpl(
    private val settings: Settings,
    engine: HttpClientEngine,
) : ApiHostRepository {

    // Держим собственный CoroutineScope: дискавери запускается из синхронного колбэка
    // ConnectionFailureHandler (см. safeRequest), а не из вызова suspend-функции репозитория.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val discoveryLock = Mutex()

    // Отдельный клиент для health-check: без Auth/refresh-плагинов основного HttpClient.
    // expectSuccess=false нужен, чтобы прочитать ожидаемый 401 как обычный ответ.
    private val probeClient = HttpClient(engine) { expectSuccess = false }

    private val hostState = MutableStateFlow(initialHost())

    override val availableHosts: List<String> = API_HOSTS
    override val currentHost: StateFlow<String> = hostState.asStateFlow()

    init {
        ConnectionFailures.handler = ConnectionFailureHandler { scope.launch { discover() } }
    }

    /**
     * Версия 1 сохраняла автоматически найденный хост и ошибочно принимала HTTP 404 за успешный
     * health-check. Один раз сбрасываем такой выбор на основной сервер; дальнейший ручной выбор
     * пользователя остаётся персистентным, а автоматический failover живёт только до перезапуска.
     */
    private fun initialHost(): String {
        val preferenceVersion = settings.getInt(KEY_API_HOST_PREFERENCE_VERSION, 0)
        if (preferenceVersion < API_HOST_PREFERENCE_VERSION) {
            settings.putString(KEY_API_HOST, PRIMARY_API_HOST)
            settings.putInt(KEY_API_HOST_PREFERENCE_VERSION, API_HOST_PREFERENCE_VERSION)
            return PRIMARY_API_HOST
        }
        return settings.getStringOrNull(KEY_API_HOST)
            ?.takeIf { it in API_HOSTS }
            ?: PRIMARY_API_HOST
    }

    override suspend fun selectHost(host: String) {
        settings.putString(KEY_API_HOST, host)
        hostState.value = host
    }

    /**
     * Перебирает [API_HOSTS] по порядку, всегда начиная со smarttvcdn. Переключение разрешено
     * только если основной сервер не вернул ожидаемый ответ, а кандидат подтвердил совместимый
     * API. Автоматический выбор не сохраняется: следующий запуск снова начинает с primary.
     */
    private suspend fun discover() {
        if (!discoveryLock.tryLock()) return
        try {
            for (host in API_HOSTS) {
                val compatible = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                    runCatching { probeClient.get("$host/$PROBE_PATH").status }
                        .getOrNull() == HttpStatusCode.Unauthorized
                } ?: false
                if (compatible) {
                    hostState.value = host
                    return
                }
            }
        } finally {
            discoveryLock.unlock()
        }
    }
}
