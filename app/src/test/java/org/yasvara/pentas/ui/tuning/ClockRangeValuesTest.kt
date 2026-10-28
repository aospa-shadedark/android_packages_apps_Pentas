/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.tuning

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockRangeValuesTest {
    private val frequencies = listOf(300L, 600L, 900L, 1_200L)

    @Test fun ordersCrossedSavedBounds() {
        assertEquals(1 to 3, clockRangeIndices(frequencies, 1_200L, 600L))
    }

    @Test fun clampsSavedBoundsToAvailableFrequencies() {
        assertEquals(0 to 3, clockRangeIndices(frequencies, -1L, Long.MAX_VALUE))
    }

    @Test fun emptyFrequencyTableIsSafe() {
        assertEquals(0 to 0, clockRangeIndices(emptyList(), 1L, 2L))
    }
}
