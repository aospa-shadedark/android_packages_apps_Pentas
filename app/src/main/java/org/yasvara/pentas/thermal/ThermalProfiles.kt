/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.thermal

import org.yasvara.pentas.charging.ChargingProfiles
import org.yasvara.pentas.device.DeviceSupport

/** Thermal profile catalog; sconfig values match the sm8450 vendor thermal map, SS names match the daemon. */
object ThermalProfiles {

    /** Settings.System keys — shared contract with SystemUI QS tiles. */
    const val KEY_THERMAL_PROFILE = "mithermal_thermal_profile"
    const val KEY_TEMP_LIMIT = "mithermal_temp_limit"
    const val KEY_TEMP_OFFSET = "mithermal_temp_offset" // Feature B, signed deci-°C
    const val KEY_CHARGE_MAX_WATT = "mithermal_charge_max_watt"
    const val KEY_CHARGE_MIN_WATT = "mithermal_charge_min_watt"
    const val KEY_APP_PROFILES = "mithermal_app_profiles"
    const val KEY_CUSTOM_PROFILES = "mithermal_custom_profiles"
    const val KEY_BYPASS_CHARGING = "mithermal_bypass_charging"
    const val KEY_HBM_ENABLED = "hbm_force_enabled"
    const val KEY_HBM_TIMEOUT = "hbm_timeout_minutes"
    const val KEY_DC_DIMMING = "dc_dimming_enabled"

    /** Properties consumed by the mi_thermal_engine Rust daemon (1 Hz loop). */
    const val PROP_TEMP_LIMIT = "persist.sys.mithermal.temp_limit"
    const val PROP_MAX_FCC = "persist.sys.mithermal.max_fcc"
    const val PROP_MIN_FCC = "persist.sys.mithermal.min_fcc"
    const val PROP_SS_PROFILE = "persist.sys.mithermal.ss_profile"
    const val PROP_CUSTOM_SEQ = "persist.sys.mithermal.custom.seq"
    const val PROP_CUSTOM_COUNT = "persist.sys.mithermal.custom.n"
    const val PROP_CUSTOM_CHUNK_PREFIX = "persist.sys.mithermal.custom."

    /** Aggressiveness knob (Feature B): signed deci-°C headroom. Positive = run
     *  hotter before throttling. Global; read on every daemon config reload. */
    const val PROP_TEMP_OFFSET = "persist.sys.mithermal.temp_offset"

    /** Extended custom-profile side channels (Features A/C/D), re-read on a
     *  custom-seq bump. CSV formats:
     *    gpu: "trig,clr,freqHz;…"   mon: "boost,hotplug,bl,blcap"   sic: "target,maxfcc" */
    const val PROP_CUSTOM_GPU = "persist.sys.mithermal.custom.gpu"
    const val PROP_CUSTOM_MON = "persist.sys.mithermal.custom.mon"
    const val PROP_CUSTOM_SIC = "persist.sys.mithermal.custom.sic"

    /** Property values are capped at 92 bytes — chunk payloads below that. */
    const val PROP_CHUNK_SIZE = 88
    const val PROP_CHUNK_MAX = 64

    /** Max GPU throttle levels a custom profile may define (matches the daemon). */
    const val CUSTOM_GPU_MAX = 4

    /** Profile indices >= this reference user-created custom profiles. */
    const val CUSTOM_PROFILE_BASE = 1000

    const val PROFILE_AUTO = 0
    const val PROFILE_PERFORMANCE = 2
    const val PROFILE_BATTERY_SAVER = 16

    /** Per-app index 0 means "follow global"; readers treat a stored 0 as no entry. */
    const val PROFILE_INHERIT = 0

    /** The SS profile name the daemon uses to load user-defined cluster curves. */
    const val SS_NAME_CUSTOM = "custom"

    /**
     * index → sconfig on the sm8450 vendor thermal map (the ids XiaomiParts writes);
     * -1 = Auto (don't override), -2 = Battery Saver.
     */
    val PROFILE_SCONFIG = intArrayOf(
        -1, //  0: Auto
        0, //  1: Default (thermal-normal)
        10, //  2: Performance (thermal-nolimits)
        20, //  3: Balanced Gaming (thermal-mgame)
        13, //  4: Heavy Gaming (thermal-tgame)
        20, //  5: Genshin Impact (thermal-mgame)
        20, //  6: Honkai Star Rail (thermal-mgame)
        13, //  7: High FPS (thermal-tgame)
        12, //  8: Camera (thermal-camera)
        12, //  9: 4K Recording (thermal-camera)
        21, // 10: Video (thermal-video)
        4, // 11: Video Chat (thermal-videochat)
        19, // 12: Navigation (thermal-navigation)
        8, // 13: Phone Call (thermal-phone)
        0, // 14: AR/VR (thermal-normal)
        0, // 15: Data Transfer (thermal-normal)
        -2, // 16: Battery Saver (powersave_mode=1)
    )

    /** index → SS profile name for the Rust daemon (must match ss.rs). */
    val PROFILE_SS_NAMES = arrayOf(
        "default", "default", "performance", "gaming", "heavy_gaming",
        "genshin", "hsr", "high_fps", "camera", "recording_4k", "video",
        "video_chat", "navigation", "phone_call", "arvr", "data_transfer",
        "battery_saver",
    )

    /** Human-readable names (logging + UI fallback). */
    val PROFILE_NAMES = arrayOf(
        "Auto", "Default", "Performance", "Balanced Gaming", "Heavy Gaming",
        "Genshin Impact", "Honkai Star Rail", "High FPS", "Camera",
        "4K Recording", "Video", "Video Chat", "Navigation", "Phone Call",
        "AR/VR", "Data Transfer", "Battery Saver",
    )

    /** Profiles that benefit from migt/metis frame boost + WALT sched boost. */
    fun isGamingSconfig(sconfig: Int): Boolean = when (sconfig) {
        10, 13, 20 -> true
        else -> false
    }

    fun isValidIndex(index: Int): Boolean =
        (index >= 0 && index < PROFILE_SCONFIG.size) || index >= CUSTOM_PROFILE_BASE

    // Watt <-> µA conversion lives in ChargingProfiles — the single converter.
    const val MIN_WATT = 5
    val MAX_WATT: Int get() = ChargingProfiles.MAX_WATT.toInt()
    const val DEFAULT_TEMP_LIMIT_DECI_C = 380 // 38.0°C

    // ── Single parser for custom profiles (eliminates 4x duplication) ──

    data class CustomProfileEntry(val index: Int, val name: String, val id: String)

    /**
     * Parse custom profiles from JSON. All consumers use this.
     * O(n) single pass, no duplicate parsing.
     */
    fun parseCustomProfiles(json: String?): List<CustomProfileEntry> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                CustomProfileEntry(
                    index = CUSTOM_PROFILE_BASE + i,
                    name = obj.optString("name", "Custom ${i + 1}"),
                    id = obj.optString("id", ""),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** One selectable entry in any thermal-profile picker. */
    data class ProfileOption(val index: Int, val name: String) {
        val isCustom get() = index >= CUSTOM_PROFILE_BASE
    }

    /**
     * Unique vendor thermal profiles mapping to distinct underlying sconfig behaviors.
     * Omits duplicate alias presets (e.g. Genshin/Honkai pointing to Balanced Gaming,
     * 4K Recording pointing to Camera, AR/VR pointing to Default).
     */
    val UNIQUE_PROFILE_INDICES = listOf(
        0,  // Auto (-1)
        1,  // Default (0)
        2,  // Performance (10)
        3,  // Balanced Gaming (20)
        4,  // Heavy Gaming (13)
        8,  // Camera (12)
        10, // Video (21)
        11, // Video Chat (4)
        12, // Navigation (19)
        13, // Phone Call (8)
        16, // Battery Saver (-2)
    )

    /**
     * Built-in scenes plus custom profiles. [firstName] renames index 0 ("Auto" globally,
     * "Follow global" per app); [builtinIndices] restricts the built-ins.
     */
    fun options(
        customJson: String?,
        firstName: String? = null,
        builtinIndices: List<Int> = UNIQUE_PROFILE_INDICES,
    ): List<ProfileOption> {
        val builtin = builtinIndices.map { i ->
            ProfileOption(i, if (i == 0 && firstName != null) firstName else PROFILE_NAMES[i])
        }
        // Custom curves are applied by the thermal daemon.
        if (!DeviceSupport.thermalDaemon) return builtin
        return builtin + parseCustomProfiles(customJson).map { ProfileOption(it.index, it.name) }
    }

    /** Display name for [index], custom profiles included. */
    fun nameOf(index: Int, customJson: String?): String = when {
        index in PROFILE_NAMES.indices -> PROFILE_NAMES[index]
        else -> parseCustomProfiles(customJson).find { it.index == index }?.name ?: "Custom"
    }
}
