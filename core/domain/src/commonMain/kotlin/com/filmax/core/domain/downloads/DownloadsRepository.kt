package com.filmax.core.domain.downloads

import com.filmax.core.domain.downloads.model.DownloadedItem
import kotlinx.coroutines.flow.Flow

interface DownloadsRepository {
    val downloads: Flow<List<DownloadedItem>>

    fun isDownloaded(id: Int): Flow<Boolean>

    suspend fun add(item: DownloadedItem)

    suspend fun remove(id: Int)
}
