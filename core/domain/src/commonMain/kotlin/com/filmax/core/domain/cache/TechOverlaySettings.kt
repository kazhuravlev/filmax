package com.filmax.core.domain.cache

import kotlinx.coroutines.flow.StateFlow

/**
 * Настройка «Показывать технические данные» — включает/выключает маленький оверлей в углу экрана
 * с живой диагностикой фоновых очередей и сети (см. `TechOverlay` в app). Независима от
 * [BackgroundFetchSettings]: та решает, ЧТО грузится в фоне, эта — только видно ли пользователю,
 * что там происходит. Выключение оверлея не останавливает ни одну из очередей.
 */
interface TechOverlaySettings {
    val enabled: StateFlow<Boolean>
    suspend fun setEnabled(enabled: Boolean)
}
