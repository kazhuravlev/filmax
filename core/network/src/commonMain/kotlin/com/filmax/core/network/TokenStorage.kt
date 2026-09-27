package com.filmax.core.network

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class TokenStorage(
    private val settings: Settings,
) {
    private val accessState = MutableStateFlow(settings.getStringOrNull(KEY_ACCESS))
    private val refreshState = MutableStateFlow(settings.getStringOrNull(KEY_REFRESH))

    val accessToken: Flow<String?> = accessState.asStateFlow()
    val refreshToken: Flow<String?> = refreshState.asStateFlow()

    suspend fun getAccessToken(): String? = accessState.value

    suspend fun getRefreshToken(): String? = refreshState.value

    suspend fun save(accessToken: String, refreshToken: String) {
        settings.putString(KEY_ACCESS, accessToken)
        settings.putString(KEY_REFRESH, refreshToken)
        accessState.value = accessToken
        refreshState.value = refreshToken
    }

    suspend fun clear() {
        settings.remove(KEY_ACCESS)
        settings.remove(KEY_REFRESH)
        accessState.value = null
        refreshState.value = null
    }

    fun seedIfEmpty(accessToken: String, refreshToken: String) {
        if (accessState.value != null) return
        settings.putString(KEY_ACCESS, accessToken)
        settings.putString(KEY_REFRESH, refreshToken)
        accessState.value = accessToken
        refreshState.value = refreshToken
    }

    companion object {
        const val PREFERENCES_NAME = "filmax_tokens"

        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
    }
}
