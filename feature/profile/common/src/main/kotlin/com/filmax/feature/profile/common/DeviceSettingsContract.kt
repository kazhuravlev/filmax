package com.filmax.feature.profile.common

import com.filmax.core.domain.user.model.DeviceSettings

data class DeviceSettingsState(
    val settings: DeviceSettings? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
)

sealed interface DeviceSettingsEvent {
    data class SetSsl(val enabled: Boolean) : DeviceSettingsEvent
    data class SetHevc(val enabled: Boolean) : DeviceSettingsEvent
    data class SetHdr(val enabled: Boolean) : DeviceSettingsEvent
    data class Set4k(val enabled: Boolean) : DeviceSettingsEvent
    data class SetMixedPlaylist(val enabled: Boolean) : DeviceSettingsEvent
    data class SetStreamingType(val streamingType: Int) : DeviceSettingsEvent
    data object Save : DeviceSettingsEvent
}

sealed interface DeviceSettingsSideEffect {
    data object Saved : DeviceSettingsSideEffect
}
