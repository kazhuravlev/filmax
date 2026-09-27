package com.filmax.data.user.remote

internal data class UpdateDeviceSettingsParams(
    val id: Int,
    val supportSsl: Int,
    val supportHevc: Int,
    val supportHdr: Int,
    val support4k: Int,
    val mixedPlaylist: Int,
    val streamingType: Int,
    val serverLocation: Int,
)
