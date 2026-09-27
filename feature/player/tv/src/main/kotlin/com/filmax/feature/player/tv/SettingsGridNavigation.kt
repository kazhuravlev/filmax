package com.filmax.feature.player.tv

internal object SettingsGridNavigation {
    fun neighbourColumn(current: Int, delta: Int, size: Int, rows: Int, isEnabled: (Int) -> Boolean): Int? {
        val row = current % rows
        val columns = (size + rows - 1) / rows
        val direction = if (delta < 0) (current / rows - 1 downTo 0) else (current / rows + 1 until columns)
        return direction.asSequence()
            .mapNotNull { column ->
                (listOf(row) + (0 until rows)).asSequence()
                    .map { column * rows + it }
                    .firstOrNull { it in 0 until size && isEnabled(it) }
            }
            .firstOrNull()
    }

    fun sameColumnNeighbour(current: Int, delta: Int, size: Int, rows: Int, isEnabled: (Int) -> Boolean): Int? {
        val row = current % rows + delta
        if (row !in 0 until rows) return null
        val index = current + delta
        return index.takeIf { it in 0 until size && isEnabled(it) }
    }

    fun firstEnabled(size: Int, isEnabled: (Int) -> Boolean): Int =
        (0 until size).firstOrNull(isEnabled) ?: 0
}
