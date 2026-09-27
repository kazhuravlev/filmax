package com.filmax.feature.profile.common

import com.filmax.core.domain.cache.ImageCacheStats
import com.filmax.core.domain.playback.PlaybackSettings
import com.filmax.core.domain.playback.PlayerUi
import com.filmax.core.domain.playback.QualityPreference
import com.filmax.core.domain.playback.TrackPreset
import com.filmax.core.domain.user.model.UserProfile

data class ProfileState(
    val profile: UserProfile? = null,
    val watchedCount: Int = 0,
    val favoritesCount: Int = 0,
    val quality: String? = null,
    val playback: PlaybackSettings = PlaybackSettings(),
    val apiHost: String = "",
    val availableApiHosts: List<String> = emptyList(),
    val imageProxyEnabled: Boolean = true,
    val backgroundFetchEnabled: Boolean = true,
    val techOverlayEnabled: Boolean = false,
    val imageCacheStats: ImageCacheStats = ImageCacheStats(),
    val itemCacheCount: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
)

sealed interface ProfileEvent {
    data object Logout : ProfileEvent
    data class SetQuality(val quality: QualityPreference) : ProfileEvent

    data class SetPreset(val preset: TrackPreset?) : ProfileEvent

    data object ResetTitleTracks : ProfileEvent

    data class SetPlayerUi(val ui: PlayerUi) : ProfileEvent
    data class SetApiHost(val host: String) : ProfileEvent
    data object ClearImageCache : ProfileEvent
    data class SetImageProxyEnabled(val enabled: Boolean) : ProfileEvent
    data class SetBackgroundFetchEnabled(val enabled: Boolean) : ProfileEvent
    data class SetTechOverlayEnabled(val enabled: Boolean) : ProfileEvent
    data object ClearItemCache : ProfileEvent
}

sealed interface ProfileSideEffect {
    data object LoggedOut : ProfileSideEffect
}
