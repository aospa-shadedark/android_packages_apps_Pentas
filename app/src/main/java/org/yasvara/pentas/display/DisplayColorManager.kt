/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.display

import android.content.Context
import android.hardware.display.ColorDisplayManager
import org.yasvara.pentas.device.DeviceProfile
import org.yasvara.pentas.device.DeviceProfiles

/** Applies a game's display colour mode and restores the user's mode afterwards. */
class DisplayColorManager private constructor(
    context: Context,
    private val deviceProfile: DeviceProfile,
) {
    private val cdm: ColorDisplayManager? = runCatching {
        context.getSystemService(ColorDisplayManager::class.java)
    }.getOrNull()

    private var originalColorMode: Int? = null

    val styleOptions: List<Pair<String, Int>>
        get() {
            if (cdm == null) return emptyList()
            val labels = if (deviceProfile.usesAospColorFallback) {
                listOf("Natural" to 0, "Boosted" to 1, "Saturated" to 2, "Automatic" to 3)
            } else {
                listOf("Original" to 0, "Vivid" to 1, "Saturated" to 2, "P3" to 3, "sRGB" to 4)
            }
            return labels.filter { (_, style) -> style in deviceProfile.colorModeMap }
        }

    fun applyForGame(style: Int) {
        if (cdm == null) return
        if (originalColorMode == null) {
            originalColorMode = runCatching { cdm?.colorMode }.getOrNull()
        }
        applyStyle(style)
    }

    fun applyStyle(style: Int): Boolean {
        val manager = cdm ?: return false
        val target = deviceProfile.colorModeMap[style] ?: return false
        return runCatching {
            manager.setColorMode(target)
            manager.colorMode == target
        }.getOrDefault(false)
    }

    fun restore() {
        if (cdm == null) return
        originalColorMode?.let {
            runCatching { cdm?.setColorMode(it) }
        }
        originalColorMode = null
    }

    companion object {
        @Volatile
        private var INSTANCE: DisplayColorManager? = null

        fun get(context: Context): DisplayColorManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DisplayColorManager(
                    context.applicationContext,
                    DeviceProfiles.get(context.applicationContext)
                ).also { INSTANCE = it }
            }
        }
    }
}
