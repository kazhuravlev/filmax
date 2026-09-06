package com.filmax.core.domain.cache

import kotlinx.coroutines.flow.StateFlow

/**
 * Единый выключатель ВСЕЙ автоматической фоновой докачки — и картинок ([ImagePrefetcher]), и
 * информации о тайтлах ([TitleBackgroundFetcher]). Раньше настройка в профиле выключала только
 * картинки — фоновую докачку тайтлов было нельзя остановить вовсе. Оба фетчера читают [enabled]
 * перед тем, как реально сходить в сеть за следующим элементом своей очереди: выключение не
 * отменяет уже стоящее в очереди, а просто перестаёт открывать сеть на новые элементы (тот же
 * принцип, что раньше был только у картинок).
 */
interface BackgroundFetchSettings {
    val enabled: StateFlow<Boolean>
    suspend fun setEnabled(enabled: Boolean)
}
