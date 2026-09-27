package com.filmax.core.domain.cache

import kotlinx.coroutines.flow.StateFlow

interface ImageProxyRepository {
    val enabled: StateFlow<Boolean>
    suspend fun setEnabled(enabled: Boolean)
}
