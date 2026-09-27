package com.filmax.core.domain.network

import kotlinx.coroutines.flow.StateFlow

interface ApiHostRepository {
    val availableHosts: List<String>

    val currentHost: StateFlow<String>

    suspend fun selectHost(host: String)
}
