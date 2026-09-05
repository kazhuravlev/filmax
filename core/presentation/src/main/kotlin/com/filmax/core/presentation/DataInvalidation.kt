package com.filmax.core.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Домены данных, которые меняет один экран, а показывает — другой (разные ScreenModel, разные
 * модули: достучаться друг до друга напрямую им нечем). Список растёт по мере необходимости —
 * не общее «обнови всё», а именно то, что реально пересекается между экранами.
 */
enum class DataDomain {
    /** Прогресс/статус просмотра — `watching/toggle`, `marktime`, отметка «Я смотрю». */
    WATCHING,

    /** Подборки-закладки — создание/удаление папки, добавление/удаление тайтла из неё. */
    BOOKMARKS,
}

/**
 * Экран-мутатор (например, экран деталей) помечает домен грязным сразу после успешного изменения.
 * Экран-читатель (например, «Я смотрю» в библиотеке), вернувшись на экран, спрашивает
 * [consumeDirty]: если грязно — тихо обновляет данные в фоне, не трогая уже показанное; если
 * нет — оставляет всё как есть, без лишнего похода в сеть.
 *
 * Процесс-широкий синглтон, не пул на ScreenModel: экраны-читатель и -мутатор живут в разных
 * Gradle-модулях и не видят ViewModelStore друг друга. Не переживает смерть процесса — и не
 * должен: холодный старт и так делает настоящий фетч в каждом ScreenModel.init.
 */
object DataInvalidation {
    private val dirty = MutableStateFlow<Set<DataDomain>>(emptySet())

    fun markDirty(vararg domains: DataDomain) {
        dirty.update { it + domains }
    }

    /** Одноразовое потребление: true, если домен был грязным — и сразу же снимает пометку. */
    fun consumeDirty(domain: DataDomain): Boolean {
        var wasDirty = false
        dirty.update { current ->
            if (domain in current) {
                wasDirty = true
                current - domain
            } else {
                current
            }
        }
        return wasDirty
    }
}
