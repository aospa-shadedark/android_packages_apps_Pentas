/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.thermal

/** Kernel nodes Pentas writes on onyx; the thermal daemon reads the matching persist.sys.mithermal.* props. */
object ThermalNodes {

    // ── mi_thermald thermal_message interface (thermal .ko) ────────────────
    const val SCONFIG = "/sys/class/thermal/thermal_message/sconfig"
    const val THERMAL_BOOST = "/sys/class/thermal/thermal_message/boost"
    const val CPU_NOLIMIT_TEMP = "/sys/class/thermal/thermal_message/cpu_nolimit_temp"
    const val SCREEN_STATE = "/sys/class/thermal/thermal_message/screen_state"

    // ── mi_thermald battery saver interface ────────────────────────────────
    const val POWERSAVE_MODE = "/sys/class/thermal/power_save/powersave_mode"
    const val POWER_LEVEL = "/sys/class/thermal/power_save/power_level"

    // ── migt.ko — Xiaomi frame-boost scheduler module ───────────────────────
    const val MIGT_FRAME_BOOST = "/sys/module/migt/parameters/frame_boost_enable"
    const val MIGT_FLT_TARGET_FPS = "/sys/module/migt/parameters/flt_target_fps"
    const val MIGT_FLT_PREBOOST = "/sys/module/migt/parameters/flt_preboost_enable"
    const val MIGT_GLK_DISABLE = "/sys/module/migt/parameters/glk_disable"

    // ── metis.ko — Xiaomi VIP-task scheduler module ─────────────────────────
    const val METIS_FBOOST = "/sys/module/metis/parameters/mi_fboost_enable"

    // ── WALT scheduler (walt.ko, /proc interface) ───────────────────────────
    const val SCHED_BOOST = "/proc/sys/walt/sched_boost"

    // ── MCA charging (xm_power .ko) ─────────────────────────────────────────
    // WIRED_CHG_CURR/2 are OWNED BY mi_thermal_engine (sic.rs write_fcc). It
    // rewrites them every cycle and detects desync by readback, so anything
    // writing them from here loses the race. Set persist.sys.mithermal.max_fcc
    // / min_fcc instead. Kept as constants for diagnostics reads only.
    const val WIRED_CHG_CURR =
        "/sys/class/xm_power/charger/charger_thermal/wired_chg_curr"
    const val WIRED_CHG_CURR2 =
        "/sys/class/xm_power/charger/charger_thermal/wired_chg_curr2"
    const val SMART_NIGHT = "/sys/class/xm_power/charger/smart_charge/smart_night"

    const val INPUT_SUSPEND =
        "/sys/devices/platform/soc/soc:mca_charge_interface/input_suspend"

    // Bypass charging (mca_smart_charge): keeps VSYS on the adapter and regulates the pack to 0 A.
    // Write BYPASS_MODE_*; BYPASS_STATE reads "<state> <drain_mA> <fcc_mA>", drain discharge-positive.
    const val BYPASS_CHARGE = "/sys/class/xm_power/charger/smart_charge/bypass_charge"
    const val BYPASS_STATE =
        "/sys/class/xm_power/charger/smart_charge/bypass_charge_state"

    // Live regulator tuning: write "<key> <value>", read back the whole set.
    // Keys: target fcc_max kp deadband step in9v in5v. Kernel clamps every
    // write, so a bad value is refused rather than applied.
    const val BYPASS_PARAMS =
        "/sys/class/xm_power/charger/smart_charge/bypass_params"

    const val BYPASS_MODE_OFF = 0
    const val BYPASS_MODE_FORCE = 1
    const val BYPASS_MODE_AUTO = 2

    /** Mirrors enum mca_bypass_chg_state in mca_smart_charge.h. */
    const val BYPASS_ST_IDLE = 0
    const val BYPASS_ST_ACTIVE = 1
    /** Not entered by the current kernel. */
    const val BYPASS_ST_DEFICIT = 2
    /** Regulator at its ceiling and the pack still draining: the input cannot carry the load. */
    const val BYPASS_ST_ASSIST = 3

    // ── Display (mi_display .ko) ────────────────────────────────────────────
    const val DISP_PARAM = "/sys/class/mi_display/disp-DSI-0/disp_param"
    const val DISP_PARAM_HBM_ON = "01 1"
    const val DISP_PARAM_HBM_OFF = "01 0"
    const val DISP_PARAM_DC_ON = "08 1"
    const val DISP_PARAM_DC_OFF = "08 0"

    // ── CPU cpufreq policies: indexed and named at runtime by DeviceProfiles ─
    fun cpuScalingMax(policyDir: String) = "$policyDir/scaling_max_freq"
    fun cpuScalingMin(policyDir: String) = "$policyDir/scaling_min_freq"
    fun cpuGovernor(policyDir: String) = "$policyDir/scaling_governor"
    fun cpuAvailableFreqs(policyDir: String) = "$policyDir/scaling_available_frequencies"
    fun cpuAvailableGovernors(policyDir: String) = "$policyDir/scaling_available_governors"
    fun cpuHwMax(policyDir: String) = "$policyDir/cpuinfo_max_freq"
    fun cpuHwMin(policyDir: String) = "$policyDir/cpuinfo_min_freq"
    fun cpuCurFreq(policyDir: String) = "$policyDir/scaling_cur_freq"

}
