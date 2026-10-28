/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.data

import android.app.GameManager
import android.app.GameModeConfiguration
import android.content.Context
import android.os.Parcel
import android.os.ServiceManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.yasvara.pentas.data.settings.TouchSettingsStore
import org.yasvara.pentas.display.DisplayColorManager
import org.yasvara.pentas.session.ActiveSessionStore
import org.yasvara.pentas.thermal.ThermalProfiles
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Per-game settings stored as `{"<pkg>": value}` maps in Settings.System. Setters apply to the
 * hardware at once when the package is the live session.
 */
@Singleton
class PerAppSettingStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val io: SettingsIO,
    private val session: ActiveSessionStore,
    private val touch: TouchSettingsStore,
) {
    private val display by lazy { DisplayColorManager.get(context) }
    private fun live(pkg: String) = session.currentPackage == pkg

    /** Render scale; GameManagerService applies it through the game's custom game mode. */
    fun resolution(pkg: String) = resolutionState(pkg).scale.toString()

    fun resolutionState(pkg: String): RenderResolutionState = runCatching {
        val games = context.getSystemService(GameManager::class.java)
            ?: return@runCatching RenderResolutionState(unavailableReason = "Render resolution is unavailable on this ROM.")
        val info = games.getGameModeInfo(pkg)
            ?: return@runCatching RenderResolutionState(unavailableReason = "Android does not support render resolution for this app.")
        val scale = if (info.activeGameMode == GameManager.GAME_MODE_CUSTOM) {
            info.getGameModeConfiguration(GameManager.GAME_MODE_CUSTOM)?.scalingFactor ?: 1f
        } else 1f
        val available = GameManager.GAME_MODE_CUSTOM in info.availableGameModes && info.isDownscalingAllowed
        RenderResolutionState(
            scale = normalizeRenderScale(scale.toString()),
            available = available,
            unavailableReason = if (available) null else "This game does not allow render resolution changes.",
        )
    }.getOrElse {
        Log.w("Pentas", "Render resolution support for $pkg could not be checked", it)
        RenderResolutionState(unavailableReason = "Render resolution is unavailable on this ROM.")
    }

    fun setResolution(pkg: String, factor: String): Boolean {
        if (!resolutionState(pkg).available) return false
        val scale = normalizeRenderScale(factor)
        if (!applyResolution(pkg, scale)) return false
        io.putPerApp(KEY_RESOLUTION, pkg, scale.toString().takeIf { scale < 1f })
        return true
    }

    /** Takes effect the next time the game starts; GameManagerService keeps it across reboots. */
    private fun applyResolution(pkg: String, scale: Float): Boolean =
        runCatching {
            val games = context.getSystemService(GameManager::class.java) ?: return@runCatching false
            val safeScale = normalizeRenderScale(scale.toString())
            if (safeScale < 1f) {
                games.updateCustomGameModeConfiguration(
                    pkg, GameModeConfiguration.Builder().setScalingFactor(safeScale).build(),
                )
                games.setGameMode(pkg, GameManager.GAME_MODE_CUSTOM)
            } else if (games.getGameMode(pkg) == GameManager.GAME_MODE_CUSTOM) {
                games.setGameMode(pkg, GameManager.GAME_MODE_STANDARD)
            }
            val applied = resolutionState(pkg)
            applied.available && abs(applied.scale - safeScale) < 0.001f
        }.onFailure { Log.w("Pentas", "Render scale for $pkg not applied", it) }.getOrDefault(false)

    fun displayStyleOptions(): List<Pair<String, Int>> = display.styleOptions

    fun displayStyle(pkg: String) = io.getPerAppInt(KEY_DISPLAY_STYLE, pkg, 0)
    fun setDisplayStyle(pkg: String, mode: Int): Boolean {
        if (display.styleOptions.none { it.second == mode }) return false
        if (live(pkg) && !display.applyStyle(mode)) return false
        io.putPerApp(KEY_DISPLAY_STYLE, pkg, mode.takeIf { it > 0 })
        return true
    }

    fun thermalProfile(pkg: String): Int = io.getPerAppInt(ThermalProfiles.KEY_APP_PROFILES, pkg, 0)
    fun setThermalProfile(pkg: String, index: Int) =
        io.putPerApp(ThermalProfiles.KEY_APP_PROFILES, pkg, index.takeIf { it > 0 })

    /** Forces GPU (client) composition while the game is in front. */
    fun gpuComposition(pkg: String) = pkg in gpuCompositionApps()
    fun setGpuComposition(pkg: String, on: Boolean) {
        val apps = gpuCompositionApps().toMutableSet().apply { if (on) add(pkg) else remove(pkg) }
        Settings.Secure.putStringForUser(context.contentResolver, KEY_GPU_COMPOSITION, apps.joinToString(","), UserHandle.USER_CURRENT)
        if (live(pkg)) forceClientComposition(on)
    }

    private fun gpuCompositionApps() = Settings.Secure.getStringForUser(
        context.contentResolver, KEY_GPU_COMPOSITION, UserHandle.USER_CURRENT,
    ).orEmpty().split(',').filter { it.isNotEmpty() }.toSet()

    /** Applied at session start, cleared at session end. */
    fun applySession(pkg: String) {
        display.applyForGame(displayStyle(pkg))
        if (gpuComposition(pkg)) forceClientComposition(true)
    }

    fun clearSession(pkg: String?) {
        display.restore()
        if (pkg != null && gpuComposition(pkg)) forceClientComposition(false)
    }

    /** SurfaceFlinger debug transaction 1008: disable hardware overlays. */
    private fun forceClientComposition(on: Boolean) = runCatching {
        val sf = ServiceManager.getService("SurfaceFlinger") ?: return@runCatching
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("android.ui.ISurfaceComposer")
            data.writeInt(if (on) 1 else 0)
            sf.transact(1008, data, reply, 0)
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    fun removeAllForPackage(pkg: String) {
        listOf(KEY_RESOLUTION, KEY_DISPLAY_STYLE, ThermalProfiles.KEY_APP_PROFILES).forEach { io.putPerApp(it, pkg, null) }
        applyResolution(pkg, 1f)
        touch.removeAll(pkg)
        setGpuComposition(pkg, false)
    }

    companion object {
        const val KEY_RESOLUTION = "pentas_game_resolution"
        const val KEY_DISPLAY_STYLE = "pentas_display_style"
        const val KEY_GPU_COMPOSITION = "disable_hw_overlays_apps"
    }
}
