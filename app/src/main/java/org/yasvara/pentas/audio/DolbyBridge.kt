/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log

/** Controls the installed Dolby implementation without attaching to its effect. */
object DolbyBridge {
    private const val TAG = "DolbyBridge"
    private const val CONTROL_AUTHORITY = "co.aospa.dolby.xiaomi.control"
    private const val LUNARIS_PACKAGE = "org.lunaris.dolby"
    private const val LUNARIS_RECEIVER = "org.lunaris.dolby.receiver.DolbyControlReceiver"
    private const val LUNARIS_SET_ENABLED = "org.lunaris.dolby.action.SET_ENABLED"
    private const val LUNARIS_SET_PROFILE = "org.lunaris.dolby.action.SET_PROFILE"
    const val SETTINGS_DAP_ACTIVE = "dolby_dap_active"
    const val SETTINGS_PROFILE_ACTIVE = "dolby_profile_active"
    val URI: Uri = Uri.parse("content://$CONTROL_AUTHORITY")

    enum class Backend { NONE, XIAOMI, LUNARIS }

    const val PROFILE_DYNAMIC = 0
    const val PROFILE_MOVIE = 1
    const val PROFILE_MUSIC = 2
    const val PROFILE_VOICE = 8
    const val PROFILE_CUSTOM = 3
    const val PROFILE_GAME = 3

    data class Status(
        val available: Boolean = false,
        val ready: Boolean = false,
        val enabled: Boolean = false,
        val profile: Int = PROFILE_DYNAMIC,
        val homeProfile: Int = PROFILE_DYNAMIC,
        val appProfilesEnabled: Boolean = false,
        val backend: Backend = Backend.NONE,
    )

    @Suppress("DEPRECATION")
    private fun controlProvider(context: Context) =
        context.packageManager.resolveContentProvider(CONTROL_AUTHORITY, 0)

    fun backend(context: Context): Backend {
        if (controlProvider(context) != null) return Backend.XIAOMI
        return try {
            context.packageManager.getPackageInfo(LUNARIS_PACKAGE, 0)
            Backend.LUNARIS
        } catch (_: PackageManager.NameNotFoundException) {
            Backend.NONE
        }
    }

    fun isAvailable(context: Context): Boolean = backend(context) != Backend.NONE

    fun status(context: Context): Status {
        return when (backend(context)) {
            Backend.XIAOMI -> {
                val reply = call(context, "get_state")
                    ?: return Status(available = true, backend = Backend.XIAOMI)
                Status(
                    available = true,
                    ready = reply.getBoolean("ready"),
                    enabled = reply.getBoolean("enabled"),
                    profile = reply.getInt("profile", PROFILE_DYNAMIC),
                    homeProfile = reply.getInt("home_profile", PROFILE_DYNAMIC),
                    appProfilesEnabled = reply.getBoolean("app_profiles_enabled"),
                    backend = Backend.XIAOMI,
                )
            }
            Backend.LUNARIS -> {
                val profile = Settings.Global.getInt(context.contentResolver,
                    SETTINGS_PROFILE_ACTIVE, PROFILE_DYNAMIC)
                Status(
                    available = true,
                    ready = true,
                    enabled = Settings.Global.getInt(context.contentResolver, SETTINGS_DAP_ACTIVE, 0) != 0,
                    profile = profile,
                    homeProfile = profile,
                    backend = Backend.LUNARIS,
                )
            }
            Backend.NONE -> Status()
        }
    }

    fun setEnabled(context: Context, enabled: Boolean): Status {
        return when (backend(context)) {
            Backend.XIAOMI -> {
                call(context, "set_enabled", extras = Bundle().apply { putBoolean("enabled", enabled) })
                status(context)
            }
            Backend.LUNARIS -> {
                val sent = sendLunaris(context, LUNARIS_SET_ENABLED) { putExtra("enabled", enabled) }
                status(context).let { if (sent) it.copy(enabled = enabled) else it }
            }
            Backend.NONE -> Status()
        }
    }

    fun setProfile(context: Context, profile: Int): Status {
        return when (backend(context)) {
            Backend.XIAOMI -> {
                call(context, "set_profile", extras = Bundle().apply { putInt("profile", profile) })
                status(context)
            }
            Backend.LUNARIS -> {
                val sent = sendLunaris(context, LUNARIS_SET_PROFILE) { putExtra("profile", profile) }
                status(context).let { if (sent) it.copy(enabled = true, profile = profile,
                    homeProfile = profile) else it }
            }
            Backend.NONE -> Status()
        }
    }

    fun openSettings(context: Context) {
        val packageName = when (backend(context)) {
            Backend.XIAOMI -> controlProvider(context)?.packageName
            Backend.LUNARIS -> LUNARIS_PACKAGE
            Backend.NONE -> null
        } ?: return
        val packageManager = context.packageManager
        val audioPanelIntent = Intent("android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL")
            .setPackage(packageName)
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: audioPanelIntent.takeIf {
                packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null
            }
            ?: return
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to open Dolby settings", exception)
        }
    }

    private inline fun sendLunaris(context: Context, action: String, build: Intent.() -> Unit): Boolean =
        try {
            context.sendBroadcast(Intent(action).apply {
                component = ComponentName(LUNARIS_PACKAGE, LUNARIS_RECEIVER)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                build()
            })
            true
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Dolby $action failed", exception)
            false
        }

    internal fun call(context: Context, method: String, arg: String? = null,
        extras: Bundle? = null): Bundle? = try {
        context.contentResolver.call(URI, method, arg, extras)?.takeIf {
            it.getBoolean("success", false)
        }
    } catch (exception: RuntimeException) {
        Log.w(TAG, "Dolby $method failed", exception)
        null
    }
}
