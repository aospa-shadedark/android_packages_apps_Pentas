/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpuTelemetryTest {
    @Test
    fun parsesPhonePercentageAndClock() {
        assertEquals(29, GpuTelemetry.parseBusy("29 %\n"))
        assertEquals(285, GpuTelemetry.frequencyMhz(285_000_000L))
    }

    @Test
    fun parsesCounterFormatWithoutOverflow() {
        assertEquals(25, GpuTelemetry.parseBusy("250 1000"))
        assertEquals(100, GpuTelemetry.parseBusy("9223372036854775807 9223372036854775807"))
    }

    @Test
    fun inaccessiblePrimaryFallsBackToCounter() {
        val values = mapOf("counter" to "300 1000")
        assertEquals(30, GpuTelemetry.readBusy(listOf("percentage", "counter"), values::get))
    }

    @Test
    fun invalidCounterFallsBackToPercentage() {
        val values = mapOf("counter" to "0 0", "percentage" to "47 %")
        assertEquals(47, GpuTelemetry.readBusy(listOf("counter", "percentage"), values::get))
    }

    @Test
    fun idlePrimaryIsNotReplacedByAnotherSample() {
        val values = mapOf("percentage" to "0 %", "counter" to "900 1000")
        assertEquals(0, GpuTelemetry.readBusy(listOf("percentage", "counter"), values::get))
    }

    @Test
    fun unavailableAndMalformedSamplesRemainUnavailable() {
        for (raw in listOf("", "garbage", "0 0", "-1 100", "NaN %", "1 invalid")) {
            assertNull(raw, GpuTelemetry.parseBusy(raw))
        }
        assertNull(GpuTelemetry.readBusy(listOf("missing")) { null })
        assertNull(GpuTelemetry.frequencyMhz(0))
    }
}
