package com.filmax.core.domain.user.model

data class DeviceOption(val id: Int, val label: String)

val streamingTypeOptions: List<DeviceOption> = listOf(
    DeviceOption(id = 0, label = "HTTP"),
    DeviceOption(id = 1, label = "HLS"),
    DeviceOption(id = 2, label = "HLS4"),
)

fun streamingTypeLabel(streamingType: Int): String =
    streamingTypeOptions.firstOrNull { it.id == streamingType }?.label ?: "Тип $streamingType"

const val SERVER_LOCATION_AUTO = 0

fun serverLocationLabel(serverLocation: Int): String =
    if (serverLocation == SERVER_LOCATION_AUTO) "Автоматически" else "Сервер $serverLocation"
