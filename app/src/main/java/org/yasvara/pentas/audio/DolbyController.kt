/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.audio

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Observable, live Dolby state shared by the overlay and game pages. */
@Singleton
class DolbyController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controlLock = Mutex()
    private val initialBackend = DolbyBridge.backend(context)
    private val _state = mutableStateOf(DolbyBridge.Status(
        available = initialBackend != DolbyBridge.Backend.NONE,
        backend = initialBackend,
    ))

    val state: DolbyBridge.Status get() = _state.value
    val isAvailable: Boolean get() = _state.value.available
    val enabledState: State<Boolean> = derivedStateOf { _state.value.enabled }

    init {
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        when (_state.value.backend) {
            DolbyBridge.Backend.XIAOMI -> context.contentResolver.registerContentObserver(
                DolbyBridge.URI, false, observer)
            DolbyBridge.Backend.LUNARIS -> {
                context.contentResolver.registerContentObserver(
                    Settings.Global.getUriFor(DolbyBridge.SETTINGS_DAP_ACTIVE), false, observer)
                context.contentResolver.registerContentObserver(
                    Settings.Global.getUriFor(DolbyBridge.SETTINGS_PROFILE_ACTIVE), false, observer)
            }
            DolbyBridge.Backend.NONE -> Unit
        }
        if (_state.value.available) refresh()
    }

    fun refresh() {
        scope.launch {
            controlLock.withLock {
                val status = DolbyBridge.status(context)
                main.post { _state.value = status }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        scope.launch {
            controlLock.withLock {
                val status = DolbyBridge.setEnabled(context, enabled)
                main.post { _state.value = status }
            }
        }
    }

    fun setProfile(profile: Int) {
        scope.launch {
            controlLock.withLock {
                val status = DolbyBridge.setProfile(context, profile)
                main.post { _state.value = status }
            }
        }
    }

    fun currentProfile(): Int = _state.value.profile
}
