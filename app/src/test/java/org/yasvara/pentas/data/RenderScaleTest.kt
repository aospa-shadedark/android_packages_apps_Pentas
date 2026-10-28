/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class RenderScaleTest {
    @Test fun malformedValuesUseDefaultScale() {
        assertEquals(1f, normalizeRenderScale(null), 0f)
        assertEquals(1f, normalizeRenderScale("not-a-number"), 0f)
        assertEquals(1f, normalizeRenderScale("NaN"), 0f)
        assertEquals(1f, normalizeRenderScale("Infinity"), 0f)
    }

    @Test fun finiteValuesStayWithinSliderRange() {
        assertEquals(0.25f, normalizeRenderScale("0.1"), 0f)
        assertEquals(0.5f, normalizeRenderScale("0.5"), 0f)
        assertEquals(1f, normalizeRenderScale("1.2"), 0f)
    }

    @Test fun settingsValuesKeepDecimalPointInCommaDecimalLocales() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("0.55", encodeRenderScale(0.55f))
            assertEquals(0.55f, normalizeRenderScale(encodeRenderScale(0.55f)), 0f)
            assertEquals("1.0", encodeRenderScale(1f))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}
