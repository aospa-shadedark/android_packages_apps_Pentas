/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.thermal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

import org.yasvara.pentas.charging.ChargingProfileRepository
import org.yasvara.pentas.session.ForegroundAppTracker
import org.yasvara.pentas.charging.ChargingProfiles
import org.yasvara.pentas.device.DeviceSupport
import org.yasvara.pentas.thermal.custom.CustomProfileRepository

/**
 * Thermal policy for mi_thermal_engine: picks the profile (global, per game, battery saver),
 * publishes charging limits, bypass, HBM and DC dimming. The daemon runs the control loops.
 */
@Singleton
class ThermalController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chargingRepo: ChargingProfileRepository,
    private val customRepo: CustomProfileRepository,
    private val foregroundTracker: ForegroundAppTracker,
) {
    private val thread = HandlerThread("PentasThermal").apply { start() }
    private val handler = Handler(thread.looper)
    private val resolver = context.contentResolver

    // ── Cached settings ─────────────────────────────────────────────────────
    private var thermalProfile = 0
    private var tempLimit = ThermalProfiles.DEFAULT_TEMP_LIMIT_DECI_C
    private var tempOffset = 0 // Feature B: aggressiveness headroom, signed deci-°C
    private var chargeMaxWatt = ThermalProfiles.MAX_WATT
    private var chargeMinWatt = ThermalProfiles.MIN_WATT
    private val appProfiles = HashMap<String, Int>()

    // ── Applied state ───────────────────────────────────────────────────────
    private var foregroundPackage = ""
    private var activeProfileIndex = -1
    private var appliedSconfig = -1
    private var originalSconfig = -1
    private var gameBoostActive = false

    /** Bumped when custom-profile JSON changes, so an edited running profile re-applies. */
    private var customRevision = 0
    private var appliedCustomRevision = -1

    // ── Battery saver integration ───────────────────────────────────────────
    private var batterySaverActive = false
    private var savedPeakRefreshRate = 120f

    // ── HBM / DC dimming state ──────────────────────────────────────────────
    private var hbmActive = false
    private var savedBrightness = 128
    private var savedAutoBrightness = 0
    private var hbmTimeoutMinutes = 5
    private var dcDimmingActive = false

    private val hbmTimeoutRunnable = Runnable {
        if (hbmActive) {
            Log.i(TAG, "HBM auto-timeout after $hbmTimeoutMinutes minutes")
            Settings.System.putIntForUser(
                resolver, ThermalProfiles.KEY_HBM_ENABLED, 0, UserHandle.USER_CURRENT
            ) // observer applies the change
        }
    }

    @Volatile
    private var started = false

    /** Idempotent — called from the Application once the process is up. */
    fun start() {
        if (started) return
        started = true
        handler.post { guarded("init") { init() } }
    }

    /** Nothing on the control thread may take the persistent process down with it. */
    private inline fun guarded(what: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.e(TAG, "$what failed", it) }
    }

    /** Property writes go to init over a socket and throw when it refuses them. */
    private fun setProp(key: String, value: String) = guarded("setprop $key") { SystemProperties.set(key, value) }

    private fun init() {
        Log.i(TAG, "ThermalController starting (mithermald control plane)")

        originalSconfig = KernelNodeIO.readInt(ThermalNodes.SCONFIG) ?: 0

        // Clear stale powersave_mode if Battery Saver is off after a crash/reboot
        val lowPower = Settings.Global.getInt(resolver, Settings.Global.LOW_POWER_MODE, 0) == 1
        if (!lowPower && KernelNodeIO.read(ThermalNodes.POWERSAVE_MODE) == "1") {
            KernelNodeIO.write(ThermalNodes.POWERSAVE_MODE, "0")
            Log.w(TAG, "Cleared stale powersave_mode=1 (Battery Saver is OFF)")
        }

        loadSettings()
        loadAppProfiles()
        registerForegroundObserver()
        registerObservers()
        registerScreenStateReceiver()
        registerChargerReceiver()

        // Prime the Rust daemon
        setProp(ThermalProfiles.PROP_TEMP_LIMIT, tempLimit.toString())
        setProp(
            ThermalProfiles.PROP_MAX_FCC,
            ChargingProfiles.wattToUa(chargeMaxWatt.toFloat()).toString()
        )
        setProp(
            ThermalProfiles.PROP_MIN_FCC,
            ChargingProfiles.wattToUa(chargeMinWatt.toFloat()).toString()
        )
        setProp(ThermalProfiles.PROP_TEMP_OFFSET, tempOffset.toString())

        applyAll()
    }

    // ── Settings loading ────────────────────────────────────────────────────

    private fun loadSettings() {
        thermalProfile = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_THERMAL_PROFILE, 0, UserHandle.USER_CURRENT
        )
        tempLimit = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_TEMP_LIMIT,
            ThermalProfiles.DEFAULT_TEMP_LIMIT_DECI_C, UserHandle.USER_CURRENT
        )
        chargeMaxWatt = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_CHARGE_MAX_WATT,
            ThermalProfiles.MAX_WATT, UserHandle.USER_CURRENT
        ).coerceIn(ThermalProfiles.MIN_WATT, ThermalProfiles.MAX_WATT)
        chargeMinWatt = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_CHARGE_MIN_WATT,
            ThermalProfiles.MIN_WATT, UserHandle.USER_CURRENT
        ).coerceIn(ThermalProfiles.MIN_WATT, chargeMaxWatt)
        if (tempLimit !in 250..480) tempLimit = ThermalProfiles.DEFAULT_TEMP_LIMIT_DECI_C
        if (!ThermalProfiles.isValidIndex(thermalProfile)) thermalProfile = 0
        tempOffset = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_TEMP_OFFSET, 0, UserHandle.USER_CURRENT
        ).coerceIn(-150, 150) // ±15.0°C in deci-°C
    }

    private fun loadAppProfiles() {
        appProfiles.clear()
        val json = Settings.System.getStringForUser(
            resolver, ThermalProfiles.KEY_APP_PROFILES, UserHandle.USER_CURRENT
        ) ?: return
        if (json.isEmpty()) return
        try {
            val obj = JSONObject(json)
            obj.keys().forEach { pkg ->
                val idx = obj.getInt(pkg)
                // Filtered here rather than at either writer: PerAppSettingStore
                // encodes "follow global" as a missing key, Settings' per-app
                // fragment as a literal 0. Normalising on read makes the map
                // sparse whichever one wrote it.
                if (idx != ThermalProfiles.PROFILE_INHERIT &&
                    ThermalProfiles.isValidIndex(idx)
                ) {
                    appProfiles[pkg] = idx
                }
            }
            Log.i(TAG, "Loaded ${appProfiles.size} per-app profiles")
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse app profiles JSON", e)
        }
    }

    // ── Foreground app detection ────────────────────────────────────────────

    private fun registerForegroundObserver() {
        // Shared with PentasService: one debounced TaskStackListener for the
        // whole process instead of a second observer doing its own binder
        // round-trip to AMS on every foreground change.
        foregroundTracker.addListener { pkg -> handler.post { guarded("foreground change") { onForegroundChanged(pkg) } } }
        Log.i(TAG, "Foreground tracking registered for per-app profiles")
    }

    private fun onForegroundChanged(topPackage: String) {
        if (topPackage == foregroundPackage) return
        foregroundPackage = topPackage
        reconcile()
    }

    // ── Apply logic ─────────────────────────────────────────────────────────

    /** The one decision of which profile is live: battery saver, then the foreground app, then global. */
    private fun resolveIndex(): Int = when {
        batterySaverActive -> ThermalProfiles.PROFILE_BATTERY_SAVER
        // A missing entry means "follow global"; loadAppProfiles has already
        // normalised a stored 0 into a missing entry.
        else -> appProfiles[foregroundPackage] ?: thermalProfile
    }

    /**
     * The single writer. Idempotent — [applyProfileIndex] drops a repeat of the
     * live (index, revision) — so every event handler can just call it after
     * updating state, instead of each one deciding what to apply.
     */
    private fun reconcile() = applyProfileIndex(resolveIndex())

    private fun applyAll() {
        reconcile()
        applyTempLimit()
        applyTempOffset()
        applyChargingProfile()
        applyBypassCharging()
        applyHbm()
        applyDcDimming()
    }

    private fun applyChargingProfile() {
        val activeId = chargingRepo.resolveActiveProfileId(foregroundPackage)
        val profile = activeId?.let { chargingRepo.getById(it) }
        chargingRepo.applyToDaemon(profile)
        if (profile == null) {
            applyChargeSpeed()
        }
    }

    /** Single entry point: applies built-in or custom (>= 1000) profiles. */
    private fun applyProfileIndex(index: Int) {
        val isCustom = index >= ThermalProfiles.CUSTOM_PROFILE_BASE

        // Custom curves are applied by the thermal daemon; without it only vendor configs exist.
        if (isCustom && !DeviceSupport.thermalDaemon) {
            applySconfig(ThermalProfiles.PROFILE_AUTO)
            return
        }

        // Applying a custom profile re-parses the whole profile list and
        // republishes it as ~15 chunked properties, each a socket round-trip to
        // init. Since reconcile() now runs on every event, that has to be
        // guarded; applySconfig already carries its own equivalent guard.
        if (isCustom &&
            index == activeProfileIndex &&
            customRevision == appliedCustomRevision
        ) {
            return
        }

        if (isCustom) {
            val customIdx = index - ThermalProfiles.CUSTOM_PROFILE_BASE
            if (!applyCustomProfileByIndex(customIdx)) {
                Log.w(TAG, "Custom profile #$customIdx not found, falling back to Default")
                applySconfig(1)
            }
        } else {
            applySconfig(index)
        }
    }

    private fun applySconfig(profileIndex: Int) {
        val sconfig = ThermalProfiles.PROFILE_SCONFIG[profileIndex]

        if (sconfig == -1) { // Auto → restore original
            if (appliedSconfig != -1 && originalSconfig >= 0) {
                KernelNodeIO.write(ThermalNodes.SCONFIG, originalSconfig)
                KernelNodeIO.write(ThermalNodes.POWERSAVE_MODE, "0")
                appliedSconfig = -1
                activeProfileIndex = -1
                applyGameBoost(false)
                setProp(ThermalProfiles.PROP_SS_PROFILE, "default")
                Log.i(TAG, "Auto → restored sconfig=$originalSconfig ss=default")
            }
            return
        }

        val targetSconfig = if (sconfig == -2) 0 else sconfig
        val powersave = sconfig == -2

        if (targetSconfig != appliedSconfig || activeProfileIndex != profileIndex) {
            KernelNodeIO.write(ThermalNodes.SCONFIG, targetSconfig)
            KernelNodeIO.write(ThermalNodes.POWERSAVE_MODE, powersave)
            appliedSconfig = targetSconfig
            activeProfileIndex = profileIndex

            val ssName = ThermalProfiles.PROFILE_SS_NAMES[profileIndex]
            setProp(ThermalProfiles.PROP_SS_PROFILE, ssName)

            val boost = ThermalProfiles.isGamingSconfig(sconfig)
            applyGameBoost(boost)

            Log.i(
                TAG, "Profile: ${ThermalProfiles.PROFILE_NAMES[profileIndex]}" +
                    " (sconfig=$targetSconfig ss=$ssName" +
                    (if (powersave) " +powersave" else "") +
                    (if (boost) " +gameBoost" else "") + ")"
            )
        }
    }

    /** Applies a custom profile; the payload format lives in [CustomProfileRepository]. */
    private fun applyCustomProfileByIndex(arrayIndex: Int): Boolean {
        val profile = customRepo.loadAll().getOrNull(arrayIndex) ?: return false
        customRepo.applyToDaemon(profile)

        // Custom profiles use the Performance vendor-thermal scene (sconfig=6).
        val customSconfig = ThermalProfiles.PROFILE_SCONFIG[ThermalProfiles.PROFILE_PERFORMANCE]
        KernelNodeIO.write(ThermalNodes.SCONFIG, customSconfig)
        KernelNodeIO.write(ThermalNodes.POWERSAVE_MODE, "0")

        // The daemon only loads the cluster curves we just published once this
        // property reads "custom" (ss.rs maybe_switch_profile). Without it the
        // chunks land but the SS algo keeps running whichever built-in profile
        // was selected last, and the seq-bump reload path — which is gated on
        // the active name already being "custom" — never fires either.
        setProp(ThermalProfiles.PROP_SS_PROFILE, ThermalProfiles.SS_NAME_CUSTOM)

        appliedSconfig = customSconfig
        activeProfileIndex = ThermalProfiles.CUSTOM_PROFILE_BASE + arrayIndex
        appliedCustomRevision = customRevision
        applyGameBoost(true)
        Log.i(TAG, "Custom profile '${profile.name}' applied (#$arrayIndex, ss=custom)")
        return true
    }

    /** Kernel game boost: migt frame boost and load tracking, metis fboost, WALT sched_boost. */
    private fun applyGameBoost(enable: Boolean) {
        if (enable == gameBoostActive) return
        gameBoostActive = enable

        KernelNodeIO.write(ThermalNodes.MIGT_FRAME_BOOST, enable)
        KernelNodeIO.write(ThermalNodes.MIGT_FLT_TARGET_FPS, if (enable) 120 else 0)
        KernelNodeIO.write(ThermalNodes.MIGT_FLT_PREBOOST, enable)
        KernelNodeIO.write(ThermalNodes.MIGT_GLK_DISABLE, !enable)
        KernelNodeIO.write(ThermalNodes.METIS_FBOOST, enable)
        KernelNodeIO.write(ThermalNodes.SCHED_BOOST, enable)
        KernelNodeIO.write(ThermalNodes.THERMAL_BOOST, enable)
        Log.i(TAG, "Game boost ${if (enable) "ENABLED" else "DISABLED"}")
    }

    private fun applyTempLimit() {
        setProp(ThermalProfiles.PROP_TEMP_LIMIT, tempLimit.toString())
    }

    private fun applyTempOffset() {
        setProp(ThermalProfiles.PROP_TEMP_OFFSET, tempOffset.toString())
    }

    /** Publishes the charge-current band as properties; the daemon owns the sysfs nodes. */
    private fun applyChargeSpeed() {
        val maxUa = ChargingProfiles.wattToUa(chargeMaxWatt.toFloat())
        val minUa = ChargingProfiles.wattToUa(chargeMinWatt.toFloat())
        setProp(ThermalProfiles.PROP_MAX_FCC, maxUa.toString())
        setProp(ThermalProfiles.PROP_MIN_FCC, minUa.toString())
        Log.d(TAG, "Charge range: $chargeMinWatt-$chargeMaxWatt W ($minUa-$maxUa µA)")
    }

    /** Bypass charging runs the system from the adapter with the pack held at 0 A. */
    private fun applyBypassCharging() {
        if (!DeviceSupport.bypassCharging) return

        val bypass = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_BYPASS_CHARGING, 0, UserHandle.USER_CURRENT
        ) == 1

        KernelNodeIO.write(
            ThermalNodes.BYPASS_CHARGE,
            if (bypass) ThermalNodes.BYPASS_MODE_FORCE
            else ThermalNodes.BYPASS_MODE_OFF
        )
    }

    // ── HBM (High Brightness Mode) ──────────────────────────────────────────

    private fun applyHbm() {
        val enable = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_HBM_ENABLED, 0, UserHandle.USER_CURRENT
        ) == 1
        hbmTimeoutMinutes = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_HBM_TIMEOUT, 5, UserHandle.USER_CURRENT
        )

        if (enable && !hbmActive) {
            savedBrightness = Settings.System.getIntForUser(
                resolver, Settings.System.SCREEN_BRIGHTNESS, 128, UserHandle.USER_CURRENT
            )
            savedAutoBrightness = Settings.System.getIntForUser(
                resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0, UserHandle.USER_CURRENT
            )
            Settings.System.putIntForUser(
                resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT
            )
            KernelNodeIO.write(ThermalNodes.DISP_PARAM, ThermalNodes.DISP_PARAM_HBM_ON)
            Settings.System.putIntForUser(
                resolver, Settings.System.SCREEN_BRIGHTNESS, 255, UserHandle.USER_CURRENT
            )
            hbmActive = true

            handler.removeCallbacks(hbmTimeoutRunnable)
            if (hbmTimeoutMinutes > 0) {
                handler.postDelayed(hbmTimeoutRunnable, hbmTimeoutMinutes * 60_000L)
            }
            Log.i(TAG, "HBM ON (saved brightness=$savedBrightness, timeout=${hbmTimeoutMinutes}min)")
        } else if (!enable && hbmActive) {
            KernelNodeIO.write(ThermalNodes.DISP_PARAM, ThermalNodes.DISP_PARAM_HBM_OFF)
            Settings.System.putIntForUser(
                resolver, Settings.System.SCREEN_BRIGHTNESS, savedBrightness,
                UserHandle.USER_CURRENT
            )
            Settings.System.putIntForUser(
                resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, savedAutoBrightness,
                UserHandle.USER_CURRENT
            )
            hbmActive = false
            handler.removeCallbacks(hbmTimeoutRunnable)
            Log.i(TAG, "HBM OFF (restored brightness=$savedBrightness)")
        }
    }

    private fun applyDcDimming() {
        val enable = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_DC_DIMMING, 0, UserHandle.USER_CURRENT
        ) == 1
        if (enable != dcDimmingActive) {
            KernelNodeIO.write(
                ThermalNodes.DISP_PARAM,
                if (enable) ThermalNodes.DISP_PARAM_DC_ON else ThermalNodes.DISP_PARAM_DC_OFF
            )
            dcDimmingActive = enable
        }
    }

    // ── Battery Saver integration ───────────────────────────────────────────

    private fun onBatterySaverChanged() {
        val lowPower = Settings.Global.getInt(resolver, Settings.Global.LOW_POWER_MODE, 0) == 1

        if (lowPower && !batterySaverActive) {
            batterySaverActive = true
            // No snapshot to take: the resolver recomputes the right profile on
            // the way out, which is also what makes a profile change *during*
            // Battery Saver survive instead of being reverted to a stale copy.
            // PROFILE_BATTERY_SAVER maps to sconfig -2, which applySconfig
            // already turns into sconfig=0 + powersave=1 + ss=battery_saver.
            reconcile()
            Log.i(TAG, "Battery Saver ON")

            runCatching {
                savedPeakRefreshRate = Settings.System.getFloatForUser(
                    resolver, "peak_refresh_rate", 120f, UserHandle.USER_CURRENT
                )
                Settings.System.putFloatForUser(
                    resolver, "peak_refresh_rate", 60f, UserHandle.USER_CURRENT
                )
            }.onFailure { Log.w(TAG, "Failed to force 60Hz", it) }
        } else if (!lowPower && batterySaverActive) {
            batterySaverActive = false
            reconcile()
            Log.i(TAG, "Battery Saver OFF → profile ${resolveIndex()}")

            runCatching {
                Settings.System.putFloatForUser(
                    resolver, "peak_refresh_rate", savedPeakRefreshRate, UserHandle.USER_CURRENT
                )
            }.onFailure { Log.w(TAG, "Failed to restore refresh rate", it) }
        }
    }

    /** Reports screen state; the daemon slows its loop while the display is off. */
    private fun registerScreenStateReceiver() {
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(
            object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: android.content.Intent) {
                    val on = intent.action == android.content.Intent.ACTION_SCREEN_ON
                    handler.post { KernelNodeIO.write(ThermalNodes.SCREEN_STATE, on) }
                }
            },
            filter,
            null,
            handler,
        )
        KernelNodeIO.write(ThermalNodes.SCREEN_STATE, true)
    }

    private fun registerChargerReceiver() {
        val filter = IntentFilter(Intent.ACTION_POWER_CONNECTED)
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    // Re-publish the band after PD negotiation settles
                    handler.postDelayed({
                        Log.i(TAG, "Charger connected: re-publishing charge band")
                        applyChargeSpeed()
                    }, 2000)
                }
            },
            filter,
            null,
            handler
        )
    }

    // ── Observers ───────────────────────────────────────────────────────────

    private fun registerObservers() {
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                guarded("setting change") { onSettingChanged(uri?.toString().orEmpty()) }
            }
        }
        arrayOf(
            ThermalProfiles.KEY_THERMAL_PROFILE,
            ThermalProfiles.KEY_TEMP_LIMIT,
            ThermalProfiles.KEY_TEMP_OFFSET,
            ThermalProfiles.KEY_CHARGE_MAX_WATT,
            ThermalProfiles.KEY_CHARGE_MIN_WATT,
            ThermalProfiles.KEY_APP_PROFILES,
            ThermalProfiles.KEY_CUSTOM_PROFILES,
            ThermalProfiles.KEY_BYPASS_CHARGING,
            ThermalProfiles.KEY_HBM_ENABLED,
            ThermalProfiles.KEY_HBM_TIMEOUT,
            ThermalProfiles.KEY_DC_DIMMING,
        ).forEach {
            resolver.registerContentObserver(
                Settings.System.getUriFor(it), false, observer, UserHandle.USER_ALL
            )
        }

        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.LOW_POWER_MODE), false,
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = guarded("battery saver change") { onBatterySaverChanged() }
            },
            UserHandle.USER_ALL
        )
    }

    /** Updates cached state, then [reconcile] decides what is live. */
    private fun onSettingChanged(uri: String) {
        loadSettings()

        when {
            uri.contains(ThermalProfiles.KEY_APP_PROFILES) -> {
                loadAppProfiles(); reconcile()
            }
            uri.contains(ThermalProfiles.KEY_CUSTOM_PROFILES) -> {
                // The payload changed; the index may not have. Bumping the
                // revision is what lets applyProfileIndex tell an edit of the
                // running profile apart from a redundant re-apply.
                customRevision++
                reconcile()
            }
            uri.contains(ThermalProfiles.KEY_BYPASS_CHARGING) -> applyBypassCharging()
            uri.contains(ThermalProfiles.KEY_HBM_ENABLED) ||
                uri.contains(ThermalProfiles.KEY_HBM_TIMEOUT) -> applyHbm()
            uri.contains(ThermalProfiles.KEY_DC_DIMMING) -> applyDcDimming()
            else -> applyAll()
        }
    }

    companion object {
        private const val TAG = "PentasThermal"
    }
}
