package com.filmax.core.ui.cache

import android.content.Context
import com.filmax.core.domain.cache.ImageProxyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Персистентность на обычных SharedPreferences — тот же подход, что и у [ImageCacheRepositoryImpl] рядом. */
internal class ImageProxyRepositoryImpl(context: Context) : ImageProxyRepository {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val enabledState = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))

    override val enabled: StateFlow<Boolean> = enabledState.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        enabledState.value = enabled
    }

    private companion object {
        const val PREFS_NAME = "filmax_image_settings"
        const val KEY_ENABLED = "proxy_enabled"
    }
}
