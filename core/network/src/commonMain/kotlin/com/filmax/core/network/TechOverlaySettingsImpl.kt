package com.filmax.core.network

import com.filmax.core.domain.cache.TechOverlaySettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val KEY_ENABLED = "enabled"

/**
 * Персистентность на отдельном [Settings] (см. `TECH_OVERLAY_SETTINGS` в DI) — свой файл, по
 * аналогии с [BackgroundFetchSettingsImpl]: сброс кэша тайтлов/изображений или токенов не должен
 * заодно менять видимость технического оверлея. По умолчанию выключен — это диагностика для
 * разработки/отладки, а не то, что должно быть на глазах у обычного пользователя из коробки.
 */
class TechOverlaySettingsImpl(private val settings: Settings) : TechOverlaySettings {

    private val enabledState = MutableStateFlow(settings.getBoolean(KEY_ENABLED, false))
    override val enabled: StateFlow<Boolean> = enabledState.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_ENABLED, enabled)
        enabledState.value = enabled
    }
}
