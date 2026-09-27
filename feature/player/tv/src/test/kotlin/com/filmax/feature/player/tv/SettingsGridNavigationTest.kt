package com.filmax.feature.player.tv

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SettingsGridNavigationTest {
    private val rows = SETTINGS_GRID_ROWS
    private val size = 6

    private fun right(from: Int, disabled: Set<Int> = emptySet()) =
        SettingsGridNavigation.neighbourColumn(from, +1, size, rows) { it !in disabled }

    private fun left(from: Int, disabled: Set<Int> = emptySet()) =
        SettingsGridNavigation.neighbourColumn(from, -1, size, rows) { it !in disabled }

    private fun up(from: Int, disabled: Set<Int> = emptySet()) =
        SettingsGridNavigation.sameColumnNeighbour(from, -1, size, rows) { it !in disabled }

    private fun down(from: Int, disabled: Set<Int> = emptySet()) =
        SettingsGridNavigation.sameColumnNeighbour(from, +1, size, rows) { it !in disabled }

    @Test
    fun `right keeps the row`() {
        assertEquals(2, right(0))
        assertEquals(3, right(1))
        assertEquals(5, right(3))
    }

    @Test
    fun `right past the last column stays put`() {
        assertNull(right(4))
        assertNull(right(5))
    }

    @Test
    fun `left from the first column leaves the grid`() {
        assertNull(left(0))
        assertNull(left(1))
    }

    @Test
    fun `up never changes the column`() {
        assertNull(up(5, disabled = setOf(4)))
        assertEquals(4, up(5))
        assertNull(up(4))
    }

    @Test
    fun `down never changes the column`() {
        assertEquals(1, down(0))
        assertNull(down(0, disabled = setOf(1)))
        assertNull(down(1))
    }

    @Test
    fun `right prefers the same row and falls back to the other row of that column`() {
        assertEquals(5, right(2, disabled = setOf(4)))
    }

    @Test
    fun `right skips a fully disabled column`() {
        assertEquals(2, right(0, disabled = setOf(0, 1)))
        assertNull(left(2, disabled = setOf(0, 1)))
    }

    @Test
    fun `right into a short last column lands on its only tile`() {
        val shortSize = 5
        assertEquals(4, SettingsGridNavigation.neighbourColumn(3, +1, shortSize, rows) { true })
        assertEquals(4, SettingsGridNavigation.neighbourColumn(2, +1, shortSize, rows) { true })
    }

    @Test
    fun `first enabled tile is the entry point`() {
        assertEquals(2, SettingsGridNavigation.firstEnabled(size) { it !in setOf(0, 1) })
        assertEquals(0, SettingsGridNavigation.firstEnabled(size) { true })
    }
}
