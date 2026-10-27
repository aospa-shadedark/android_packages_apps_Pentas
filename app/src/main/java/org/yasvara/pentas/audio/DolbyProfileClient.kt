/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.audio

import android.content.Context
import android.os.Bundle

/** Per-app assignments owned and applied by XiaomiDolby's optional profile service. */
object DolbyProfileClient {
    const val PROFILE_DEFAULT = -1
    private val XIAOMI_OPTIONS = listOf(
        "Dynamic" to DolbyBridge.PROFILE_DYNAMIC,
        "Movie" to DolbyBridge.PROFILE_MOVIE,
        "Music" to DolbyBridge.PROFILE_MUSIC,
        "Voice" to DolbyBridge.PROFILE_VOICE,
        "Custom" to DolbyBridge.PROFILE_CUSTOM,
    )
    private val LUNARIS_OPTIONS = listOf(
        "Dynamic" to DolbyBridge.PROFILE_DYNAMIC,
        "Movie" to DolbyBridge.PROFILE_MOVIE,
        "Music" to DolbyBridge.PROFILE_MUSIC,
        "Game" to DolbyBridge.PROFILE_GAME,
    )

    fun optionsFor(backend: DolbyBridge.Backend): List<Pair<String, Int>> =
        if (backend == DolbyBridge.Backend.LUNARIS) LUNARIS_OPTIONS else XIAOMI_OPTIONS

    data class Assignment(val profile: Int = PROFILE_DEFAULT, val monitoringEnabled: Boolean = false)

    fun getProfile(context: Context, pkg: String): Assignment {
        if (DolbyBridge.backend(context) != DolbyBridge.Backend.XIAOMI) return Assignment()
        val reply = DolbyBridge.call(context, "get_app_profile", pkg) ?: return Assignment()
        return Assignment(reply.getInt("profile", PROFILE_DEFAULT),
            reply.getBoolean("app_profiles_enabled"))
    }

    fun setProfile(context: Context, pkg: String, profile: Int): Assignment {
        if (DolbyBridge.backend(context) != DolbyBridge.Backend.XIAOMI) return Assignment()
        DolbyBridge.call(context, "set_app_profile", pkg,
            Bundle().apply { putInt("profile", profile) })
        return getProfile(context, pkg)
    }
}
