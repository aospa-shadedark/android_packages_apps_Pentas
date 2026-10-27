/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.telemetry

/** GPU driver formats and fallback selection, independent of Android sysfs access. */
internal object GpuTelemetry {
    private val whitespace = Regex("\\s+")

    fun readBusy(paths: List<String>, read: (String) -> String?): Int? {
        for (path in paths) {
            val value = read(path)?.let(::parseBusy) ?: continue
            return value // A valid zero is an idle GPU, not a failed read.
        }
        return null
    }

    fun parseBusy(raw: String): Int? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if ('%' in text) {
            return percentage(text.substringBefore('%').trim().toDoubleOrNull())
        }
        val fields = text.split(whitespace)
        if (fields.size == 1) return percentage(fields[0].toDoubleOrNull())
        val busy = fields[0].toLongOrNull() ?: return null
        val total = fields[1].toLongOrNull() ?: return null
        if (busy < 0 || total <= 0) return null
        // Convert before multiplying to avoid overflow on large counters.
        return percentage(busy.toDouble() / total.toDouble() * 100.0)
    }

    private fun percentage(value: Double?): Int? =
        value?.takeIf { it.isFinite() && it >= 0.0 }?.coerceIn(0.0, 100.0)?.toInt()

    fun frequencyMhz(raw: Long): Int? = when {
        raw <= 0L -> null
        raw >= 1_000_000L -> (raw / 1_000_000L).toInt() // Hz
        raw >= 1_000L -> (raw / 1_000L).toInt()       // kHz
        else -> raw.toInt()                          // MHz
    }
}
