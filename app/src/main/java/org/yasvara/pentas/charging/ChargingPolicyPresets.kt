/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.charging

/** The device's stock policy and the presets built from it. */
object ChargingPolicyPresets {

    /** Vendor normal-charge bands, verbatim. */
    val STOCK_JEITA_NORMAL = listOf(
        JeitaBand(JeitaBand.SENTINEL_LO, -9, 0, 2, 0, 4530, 572),
        JeitaBand(-9, 0, 1, 2, 940, 4530, 572),
        JeitaBand(0, 5, 1, 1, 3115, 4530, 572),
        JeitaBand(5, 10, 1, 1, 4385, 4530, 572),
        JeitaBand(10, 15, 1, 1, 7560, 4530, 572),
        JeitaBand(15, 20, 1, 1, 12640, 4530, 572),
        JeitaBand(20, 35, 1, 1, 20000, 4510, 572),
        JeitaBand(35, 40, 1, 1, 20000, 4510, 572),
        JeitaBand(40, 48, 1, 0, 20000, 4510, 572),
        // The band that made a 49 °C phone stop charging: 3115 mA and a 4100 mV
        // float, below the driver's 4200 mV cutoff gate.
        JeitaBand(48, 55, 2, 2, 3115, 4100, 572),
        JeitaBand(55, JeitaBand.SENTINEL_HI, 2, 2, 0, 4100, 572),
    )

    /** Vendor fast-full-charge bands. Note the 4580 mV float. */
    val STOCK_JEITA_FFC = listOf(
        JeitaBand(20, 35, 1, 2, 20000, 4580, 2667),
        JeitaBand(35, 40, 2, 1, 20000, 4580, 2731),
        JeitaBand(40, 48, 1, 0, 20000, 4580, 3239),
        JeitaBand(48, 55, 2, 2, 3175, 4060, 572),
        JeitaBand(55, JeitaBand.SENTINEL_HI, 2, 2, 0, 4060, 572),
    )

    /** Stock per-adapter ceilings, from mca.dtsi (onyx overrides none of them). */
    val STOCK_CAPS = AdapterCaps(
        inputMa = mapOf(
            "dcp" to 1500, "pd" to 1600, "pps" to 1500, "hvdcp" to 1200,
            "hvdcp3" to 1600, "hvdcp3p5" to 1600, "cdp" to 900, "sdp" to 500,
            "float" to 1000,
        ),
        chargeMa = mapOf(
            "dcp" to 2000, "pd" to 3000, "pps" to 2000, "hvdcp" to 2600,
            "hvdcp3" to 3600, "hvdcp3p5" to 3600, "cdp" to 900, "sdp" to 500,
            "float" to 1000,
        ),
        ffcTempLowC = 15,
        ffcTempHighC = 48,
    )

    /** Replaces bands from [fromC] up with one full-current band, keeping the float voltage. */
    private fun openHotEnd(
        bands: List<JeitaBand>,
        fromC: Int,
        toC: Int,
        fccMa: Int,
        fvMv: Int,
    ): List<JeitaBand> {
        val kept = bands.filter { it.tHiC <= fromC }
        val iterm = bands.firstOrNull()?.itermMa ?: 572
        return kept +
            JeitaBand(fromC, toC, 1, 0, fccMa, fvMv, iterm) +
            JeitaBand(toC, JeitaBand.SENTINEL_HI, 2, 2, 0, fvMv, iterm)
    }

    /** Stock behaviour, expressed as a profile. Changes nothing. */
    fun balanced() = ChargingPolicy(
        jeitaNormal = STOCK_JEITA_NORMAL,
        jeitaFfc = STOCK_JEITA_FFC,
        caps = STOCK_CAPS,
        bypass = BypassTuning(mode = BypassMode.OFF),
        dynamics = ChargeDynamics(),
    )

    /** Bypass first: pack at net zero, hot bands open to 55 °C, raised input ceilings. */
    fun marathon() = ChargingPolicy(
        jeitaNormal = openHotEnd(STOCK_JEITA_NORMAL, fromC = 40, toC = 55, fccMa = 20000, fvMv = 4510),
        jeitaFfc = openHotEnd(STOCK_JEITA_FFC, fromC = 40, toC = 55, fccMa = 20000, fvMv = 4580),
        hotFvGateMv = 4200,
        caps = STOCK_CAPS.copy(
            inputMa = STOCK_CAPS.inputMa + mapOf(
                "pd" to 3000, "pps" to 3000, "hvdcp3" to 3000, "hvdcp3p5" to 3000,
            ),
            chargeMa = STOCK_CAPS.chargeMa + mapOf("pd" to 6000, "pps" to 6000),
            ffcTempHighC = 55,
        ),
        bypass = BypassTuning(
            targetMa = 0, fccMaxMa = 3000, kp = 15, deadbandMa = 5, stepMa = 30,
            in9vMa = 3000, in5vMa = 3000, mode = BypassMode.AUTO,
        ),
        dynamics = ChargeDynamics(netzero = true, netzeroTargetMa = 0),
    )

    /** No thermal derate and forced bypass, still inside the chemistry envelope. */
    fun unrestricted() = ChargingPolicy(
        // 120 W = 20640 mA @172k. Widen the live kernel envelope first or the
        // tables below get clamped back to the 20000 power-on default.
        envelope = PolicyEnvelope(fccMaxMa = 22_000, iinMaxMa = 6_000),
        jeitaNormal = openHotEnd(STOCK_JEITA_NORMAL, fromC = 40, toC = 58, fccMa = 22000, fvMv = 4510),
        jeitaFfc = openHotEnd(STOCK_JEITA_FFC, fromC = 40, toC = 58, fccMa = 22000, fvMv = 4580),
        hotFvGateMv = 4200,
        caps = STOCK_CAPS.copy(
            inputMa = STOCK_CAPS.inputMa.mapValues { (k, v) ->
                if (k in setOf("pd", "pps", "hvdcp3", "hvdcp3p5")) 4000 else v
            },
            chargeMa = STOCK_CAPS.chargeMa.mapValues { (k, v) ->
                if (k in setOf("pd", "pps", "hvdcp3", "hvdcp3p5")) 8000 else v
            },
            ffcTempHighC = 58,
        ),
        // Every rung flat: the ladder still exists, it just stops derating.
        ladder = (1..15).map { ThermalLadderRow.flat(it, inputMa = 4000, packMa = 22000) },
        bypass = BypassTuning(
            targetMa = 0, fccMaxMa = 4000, kp = 20, deadbandMa = 5, stepMa = 50,
            in9vMa = 4000, in5vMa = 3000, mode = BypassMode.FORCE,
        ),
        dynamics = ChargeDynamics(
            netzero = true,
            netzeroTargetMa = 0,
            // No slew limit and no predictive lookahead: the point of this
            // profile is that nothing gets in the way of full current.
            slewUa = 0,
            predAheadS = 0,
            predGain = 0,
        ),
    )

    enum class Preset(val label: String, val summary: String) {
        BALANCED("Balanced", "Stock Xiaomi policy, unchanged"),
        MARATHON("Marathon", "Gaming: bypass first, hot bands open to 55 °C"),
        UNRESTRICTED("Unrestricted", "No thermal derate — full current to the safety envelope");

        fun build(supportsBypass: Boolean = true): ChargingPolicy {
            val policy = when (this) {
                BALANCED -> balanced()
                MARATHON -> marathon()
                UNRESTRICTED -> unrestricted()
            }
            return if (supportsBypass) policy else policy.copy(
                bypass = null,
                dynamics = policy.dynamics.copy(netzero = false, netzeroTargetMa = 0),
            )
        }

        fun summary(supportsBypass: Boolean): String =
            if (this == MARATHON && !supportsBypass) "Gaming thermal curve up to 55 °C" else summary
    }
}
