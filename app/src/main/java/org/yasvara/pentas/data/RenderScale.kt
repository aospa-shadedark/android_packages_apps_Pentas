/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.data

import java.util.Locale

data class RenderResolutionState(
    val scale: Float = 1f,
    val available: Boolean = false,
    val unavailableReason: String? = null,
)

/** Values persisted outside the slider can be stale or malformed; keep them safe for Compose and GameManager. */
internal fun normalizeRenderScale(value: String?): Float =
    value?.toFloatOrNull()
        ?.takeIf { it.isFinite() }
        ?.coerceIn(MIN_RENDER_SCALE, 1f)
        ?: 1f

/** Settings values must keep a decimal point even when the display locale uses a comma. */
internal fun encodeRenderScale(value: Float): String =
    "%.2f".format(Locale.ROOT, normalizeRenderScale(value.toString()))
        .trimEnd('0').trimEnd('.')
        .let { if ('.' in it) it else "$it.0" }

private const val MIN_RENDER_SCALE = 0.25f
