package com.filmax.core.network

import com.filmax.core.domain.cache.BackgroundFetchSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val KEY_ENABLED = "enabled"

/**
 * Персистентность на отдельном [Settings] (см. `BG_FETCH_SETTINGS` в DI) — свой файл, а не общий
 * с токенами/кэшем тайтлов/изображений: сброс любого из тех кэшей не должен заодно включать
 * фоновую загрузку обратно.
 */
class BackgroundFetchSettingsImpl(private val settings: Settings) : BackgroundFetchSettings {

    private val enabledState = MutableStateFlow(settings.getBoolean(KEY_ENABLED, true))
    override val enabled: StateFlow<Boolean> = enabledState.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_ENABLED, enabled)
        enabledState.value = enabled
    }
}
