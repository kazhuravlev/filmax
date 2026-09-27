package com.filmax.core.domain.cache

import kotlinx.coroutines.flow.StateFlow

interface TechOverlaySettings {
    val enabled: StateFlow<Boolean>
    suspend fun setEnabled(enabled: Boolean)
}
