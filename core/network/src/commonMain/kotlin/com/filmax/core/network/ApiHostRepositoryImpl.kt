package com.filmax.core.network

import com.filmax.core.domain.common.ConnectionFailureHandler
import com.filmax.core.domain.common.ConnectionFailures
import com.filmax.core.domain.network.ApiHostRepository
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
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
val API_HOSTS = listOf(
    "https://smarttvcdn.online",
    "https://api.boramoraboom.ru",
    "https://api.srvkp.com",
)

private const val KEY_API_HOST = "api_host"
private const val DISCOVERY_TIMEOUT_MS = 5_000L

/** Лёгкий публичный эндпоинт, не требующий токена — годится как health-check хоста. */
private const val PROBE_PATH = "api/v1/countries"

/**
 * Текущий хост API + дискавери рабочего хоста среди [API_HOSTS].
 *
 * При старте использует ранее сохранённый (или первый из списка) хост без проверки —
 * "при включении пробуем использовать его". Дискавери запускается реактивно: подписывается на
 * [ConnectionFailures] и перебирает [API_HOSTS] заново, как только [safeRequest][com.filmax.core.domain.common.safeRequest]
 * встречает Offline/Timeout у любого запроса.
 */
class ApiHostRepositoryImpl(
    private val settings: Settings,
    engine: HttpClientEngine,
) : ApiHostRepository {

    // Держим собственный CoroutineScope: дискавери запускается из синхронного колбэка
    // ConnectionFailureHandler (см. safeRequest), а не из вызова suspend-функции репозитория.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val discoveryLock = Mutex()

    // Отдельный клиент для health-check: без Auth/refresh-плагинов основного HttpClient и с
    // expectSuccess=false — любой HTTP-ответ (даже 401/404) означает «хост живой».
    private val probeClient = HttpClient(engine) { expectSuccess = false }

    private val hostState = MutableStateFlow(settings.getStringOrNull(KEY_API_HOST) ?: API_HOSTS.first())

    override val availableHosts: List<String> = API_HOSTS
    override val currentHost: StateFlow<String> = hostState.asStateFlow()

    init {
        ConnectionFailures.handler = ConnectionFailureHandler { scope.launch { discover() } }
    }

    override suspend fun selectHost(host: String) {
        settings.putString(KEY_API_HOST, host)
        hostState.value = host
    }

    /** Перебирает [API_HOSTS] по порядку; первый хост без сетевой ошибки становится текущим. */
    private suspend fun discover() {
        if (!discoveryLock.tryLock()) return
        try {
            for (host in API_HOSTS) {
                val reachable = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                    runCatching { probeClient.get("$host/$PROBE_PATH") }.isSuccess
                } ?: false
                if (reachable) {
                    selectHost(host)
                    return
                }
            }
        } finally {
            discoveryLock.unlock()
        }
    }
}
