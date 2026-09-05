package com.filmax.core.domain.downloads.model

data class DownloadedItem(
    val id: Int,
    val title: String,
    val posterSmall: String,
    val year: Int,
    val durationMinutes: Int,
)
