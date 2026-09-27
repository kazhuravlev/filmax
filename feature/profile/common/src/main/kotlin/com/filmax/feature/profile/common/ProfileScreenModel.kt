package com.filmax.feature.profile.common

import com.filmax.core.domain.auth.AuthRepository
import com.filmax.core.domain.cache.BackgroundFetchSettings
import com.filmax.core.domain.cache.ImageCacheRepository
import com.filmax.core.domain.cache.ImageProxyRepository
import com.filmax.core.domain.cache.ItemDetailsCache
import com.filmax.core.domain.cache.TechOverlaySettings
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.favorites.FavoritesRepository
import com.filmax.core.domain.network.ApiHostRepository
import com.filmax.core.domain.playback.PlaybackSettingsRepository
import com.filmax.core.domain.playback.PlayerUi
import com.filmax.core.domain.playback.QualityPreference
import com.filmax.core.domain.playback.TrackPreset
import com.filmax.core.domain.user.UserRepository
import com.filmax.core.domain.watching.WatchingRepository
import com.filmax.core.presentation.BaseScreenModel

@Suppress("LongParameterList", "TooManyFunctions")
class ProfileScreenModel(
    private val user: UserRepository,
    private val watching: WatchingRepository,
    private val auth: AuthRepository,
    private val favorites: FavoritesRepository,
    private val playbackSettings: PlaybackSettingsRepository,
    private val apiHost: ApiHostRepository,
    private val imageCache: ImageCacheRepository,
    private val imageProxy: ImageProxyRepository,
    private val backgroundFetch: BackgroundFetchSettings,
    private val itemCache: ItemDetailsCache,
    private val techOverlay: TechOverlaySettings,
) : BaseScreenModel<ProfileState, ProfileSideEffect, ProfileEvent>(ProfileState()) {
    init {
        onFetchData()
        observeFavorites()
        observePlaybackSettings()
        observeApiHost()
        observeImageProxy()
        observeBackgroundFetch()
        observeImageCacheStats()
        observeItemCache()
        observeTechOverlay()
    }

    private fun observeFavorites() {
        screenModelScope {
            favorites.favorites.collect { items ->
                updateState { it.copy(favoritesCount = items.size) }
            }
        }
    }

    private fun observePlaybackSettings() {
        screenModelScope {
            playbackSettings.settings.collect { settings ->
                updateState { it.copy(playback = settings) }
            }
        }
    }

    private fun observeApiHost() {
        screenModelScope {
            apiHost.currentHost.collect { host ->
                updateState { it.copy(apiHost = host, availableApiHosts = apiHost.availableHosts) }
            }
        }
    }

    private fun observeImageProxy() {
        screenModelScope {
            imageProxy.enabled.collect { enabled ->
                updateState { it.copy(imageProxyEnabled = enabled) }
            }
        }
    }

    private fun observeBackgroundFetch() {
        screenModelScope {
            backgroundFetch.enabled.collect { enabled ->
                updateState { it.copy(backgroundFetchEnabled = enabled) }
            }
        }
    }

    private fun observeImageCacheStats() {
        screenModelScope {
            imageCache.stats.collect { stats ->
                updateState { it.copy(imageCacheStats = stats) }
            }
        }
    }

    private fun observeItemCache() {
        screenModelScope {
            itemCache.count.collect { count ->
                updateState { it.copy(itemCacheCount = count) }
            }
        }
    }

    private fun observeTechOverlay() {
        screenModelScope {
            techOverlay.enabled.collect { enabled ->
                updateState { it.copy(techOverlayEnabled = enabled) }
            }
        }
    }

    override fun dispatch(event: ProfileEvent) {
        when (event) {
            ProfileEvent.Logout -> logout()
            is ProfileEvent.SetQuality -> setQuality(event.quality)
            is ProfileEvent.SetPreset -> setPreset(event.preset)
            ProfileEvent.ResetTitleTracks -> resetTitleTracks()
            is ProfileEvent.SetPlayerUi -> setPlayerUi(event.ui)
            is ProfileEvent.SetApiHost -> setApiHost(event.host)
            ProfileEvent.ClearImageCache -> clearImageCache()
            is ProfileEvent.SetImageProxyEnabled -> setImageProxyEnabled(event.enabled)
            is ProfileEvent.SetBackgroundFetchEnabled -> setBackgroundFetchEnabled(event.enabled)
            is ProfileEvent.SetTechOverlayEnabled -> setTechOverlayEnabled(event.enabled)
            ProfileEvent.ClearItemCache -> clearItemCache()
        }
    }

    private fun clearItemCache() = screenModelScope {
        itemCache.clear()
    }

    private fun setApiHost(host: String) = screenModelScope {
        apiHost.selectHost(host)
    }

    private fun setImageProxyEnabled(enabled: Boolean) = screenModelScope {
        imageProxy.setEnabled(enabled)
    }

    private fun setBackgroundFetchEnabled(enabled: Boolean) = screenModelScope {
        backgroundFetch.setEnabled(enabled)
    }

    private fun setTechOverlayEnabled(enabled: Boolean) = screenModelScope {
        techOverlay.setEnabled(enabled)
    }

    private fun setQuality(quality: QualityPreference) = screenModelScope {
        playbackSettings.setQuality(quality)
    }

    private fun setPreset(preset: TrackPreset?) = screenModelScope {
        playbackSettings.setPreset(preset)
    }

    private fun resetTitleTracks() = screenModelScope {
        playbackSettings.clearTitleTracks()
    }

    private fun setPlayerUi(ui: PlayerUi) = screenModelScope {
        playbackSettings.setPlayerUi(ui)
    }

    private fun clearImageCache() = screenModelScope {
        imageCache.clear()
    }

    override fun onFetchData() {
        screenModelScope { snapshot ->
            when (val result = user.getProfile()) {
                is RequestResult.Success ->
                    updateState { it.copy(loading = false, profile = result.data) }

                is RequestResult.Error -> {
                    updateState { it.copy(loading = false, error = result.message) }
                    return@screenModelScope
                }
            }

            (watching.getHistory() as? RequestResult.Success)?.let { history ->
                updateState { it.copy(watchedCount = history.data.size) }
            }
        }
    }

    private fun logout() {
        screenModelScope {
            auth.logout()
            postSideEffect(ProfileSideEffect.LoggedOut)
        }
    }
}
