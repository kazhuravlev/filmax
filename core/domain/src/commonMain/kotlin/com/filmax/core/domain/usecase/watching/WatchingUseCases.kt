package com.filmax.core.domain.usecase.watching

import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.watching.WatchingRepository

class ToggleWatchlistUseCase(private val repository: WatchingRepository) {
    suspend operator fun invoke(itemId: Int): RequestResult<Boolean> = repository.toggleWatchlist(itemId)
}

class ToggleWatchedUseCase(private val repository: WatchingRepository) {
    suspend operator fun invoke(itemId: Int): RequestResult<Boolean> = repository.toggleWatched(itemId)
}
