package com.filmax.app.update

import java.io.File

data class UpdateInfo(
    val version: String,
    val assetUrl: String,
    val sizeBytes: Long,
)

data class AppUpdateState(
    val update: UpdateInfo? = null,
    val dismissed: Boolean = false,
    val checking: Boolean = false,
    val upToDate: Boolean = false,
    val installable: Boolean = true,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val downloadedApk: File? = null,
    val downloadError: Boolean = false,
)

sealed interface AppUpdateEvent {
    data object Check : AppUpdateEvent

    data object Download : AppUpdateEvent

    data object Install : AppUpdateEvent

    data object Dismiss : AppUpdateEvent
}

sealed interface AppUpdateSideEffect {
    data class LaunchInstaller(val apk: File) : AppUpdateSideEffect
}
