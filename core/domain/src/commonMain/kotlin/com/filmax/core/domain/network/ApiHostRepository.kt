package com.filmax.core.domain.network

import kotlinx.coroutines.flow.StateFlow

/**
 * Текущий и доступные хосты API kino.watch. Отдельный контракт от auth/user: переключение
 * хоста не требует токена и не должно тянуть остальной сетевой слой в презентацию.
 *
 * Реализация (`core:network`) хранит выбор персистентно и умеет искать рабочий хост health-check'ом
 * (см. `ApiHostRepositoryImpl` и `doccs-api/API_CONTRACT.md` §1) — здесь только то, что нужно UI.
 */
interface ApiHostRepository {

    /** Хосты-кандидаты в порядке предпочтения — тот же список, что перебирает дискавери. */
    val availableHosts: List<String>

    /** Текущий выбранный хост (со схемой, без хвостового слеша). */
    val currentHost: StateFlow<String>

    /** Ручной выбор хоста из [availableHosts] — пункт настроек «Выбрать сервер». */
    suspend fun selectHost(host: String)
}
