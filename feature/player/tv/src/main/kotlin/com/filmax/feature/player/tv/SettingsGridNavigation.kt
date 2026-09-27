package com.filmax.feature.player.tv

/**
 * Геометрия сетки настроек плеера: плитки идут СТОЛБЦАМИ по [rows] штук (см. `SettingsGrid`),
 * индекс `i` стоит в столбце `i / rows` и строке `i % rows`. Курсор двигается так, как выглядит
 * на экране, а не по линейному индексу:
 *
 *  - ◄/► — в соседний столбец, предпочтительно в ту же строку; если та плитка выключена
 *    (единственная аудиодорожка, одно качество) — в другую строку того же столбца; если выключен
 *    весь столбец — дальше в следующий. Ни разу не «проваливается» по вертикали.
 *  - ▲/▼ — только внутри столбца. Выключенная плитка сверху не отправляет курсор в соседний
 *    столбец (раньше «вверх» уводил влево — линейный шаг `-1` перепрыгивал столбец), а означает
 *    выход из сетки вверх (на полосу прокрутки); снизу выключенная плитка просто не даёт хода.
 *
 * Чистая функция без Compose — чтобы раскладку пульта можно было проверить тестами.
 */
internal object SettingsGridNavigation {

    /** Индекс плитки в соседнем столбце ([delta] = ±1) или null — столбцов в ту сторону больше нет. */
    fun neighbourColumn(current: Int, delta: Int, size: Int, rows: Int, isEnabled: (Int) -> Boolean): Int? {
        val row = current % rows
        val columns = (size + rows - 1) / rows
        val direction = if (delta < 0) (current / rows - 1 downTo 0) else (current / rows + 1 until columns)
        return direction.asSequence()
            .mapNotNull { column ->
                // Сначала та же строка, затем остальные строки этого столбца.
                (listOf(row) + (0 until rows)).asSequence()
                    .map { column * rows + it }
                    .firstOrNull { it in 0 until size && isEnabled(it) }
            }
            .firstOrNull()
    }

    /** Индекс соседней плитки в том же столбце ([delta] = ±1) или null — её нет либо она выключена. */
    fun sameColumnNeighbour(current: Int, delta: Int, size: Int, rows: Int, isEnabled: (Int) -> Boolean): Int? {
        val row = current % rows + delta
        if (row !in 0 until rows) return null
        val index = current + delta
        return index.takeIf { it in 0 until size && isEnabled(it) }
    }

    /** Первая включённая плитка — точка входа в сетку с транспорта. */
    fun firstEnabled(size: Int, isEnabled: (Int) -> Boolean): Int =
        (0 until size).firstOrNull(isEnabled) ?: 0
}
