/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.tuning

/** Convert saved clock bounds to ordered, in-table slider indices. */
internal fun clockRangeIndices(table: List<Long>, min: Long, max: Long): Pair<Int, Int> {
    if (table.isEmpty()) return 0 to 0
    val lower = table.nearestIndex(min)
    val upper = table.nearestIndex(max)
    return minOf(lower, upper) to maxOf(lower, upper)
}

private fun List<Long>.nearestIndex(value: Long): Int {
    val target = value.coerceIn(first(), last())
    return indices.minByOrNull { kotlin.math.abs(this[it] - target) } ?: 0
}
