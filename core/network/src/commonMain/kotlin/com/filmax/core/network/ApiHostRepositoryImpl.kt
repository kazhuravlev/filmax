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

private const val PROBE_PATH = "api/v1/countries"

class ApiHostRepositoryImpl(
    private val settings: Settings,
    engine: HttpClientEngine,
) : ApiHostRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val discoveryLock = Mutex()

    private val probeClient = HttpClient(engine) { expectSuccess = false }

    private val hostState = MutableStateFlow(initialHost())

    override val availableHosts: List<String> = API_HOSTS
    override val currentHost: StateFlow<String> = hostState.asStateFlow()

    init {
        ConnectionFailures.handler = ConnectionFailureHandler { scope.launch { discover() } }
    }

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
