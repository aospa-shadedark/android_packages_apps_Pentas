/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.data

import android.content.Context
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared low-level I/O for Settings.System + system properties.
 * Single tested path for all per-app JSON stores.
 */
@Singleton
class SettingsIO @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val resolver get() = ctx.contentResolver

    fun read(key: String): String? =
        Settings.System.getStringForUser(resolver, key, UserHandle.USER_CURRENT)

    fun write(key: String, json: String): Boolean =
        runCatching {
            Settings.System.putStringForUser(resolver, key, json, UserHandle.USER_CURRENT)
        }.onFailure {
            Log.w(TAG, "Failed to write setting '$key'", it)
        }.getOrDefault(false)

    fun setProp(name: String, value: String): Boolean =
        runCatching {
            SystemProperties.set(name, value)
            true
        }.onFailure {
            Log.w(TAG, "Failed to set property '$name'", it)
        }.getOrDefault(false)

    fun readPropLong(name: String, default: Long = 0L): Long =
        runCatching { SystemProperties.getLong(name, default) }.getOrDefault(default)

    // ── Per-app JSON convenience (delegates to PerAppJson) ──

    fun putPerApp(key: String, pkg: String, value: Any?): Boolean =
        write(key, PerAppJson.put(read(key), pkg, value))

    fun getPerAppString(key: String, pkg: String, default: String): String =
        PerAppJson.getString(read(key), pkg, default)

    fun getPerAppInt(key: String, pkg: String, default: Int): Int =
        PerAppJson.getInt(read(key), pkg, default)

    fun getPerAppBoolean(key: String, pkg: String, default: Boolean): Boolean =
        PerAppJson.getBoolean(read(key), pkg, default)

    companion object {
        private const val TAG = "SettingsIO"
    }
}
