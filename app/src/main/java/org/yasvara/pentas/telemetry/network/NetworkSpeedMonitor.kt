/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.telemetry.network

import android.net.TrafficStats
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*

class NetworkSpeedMonitor {

    companion object {
        private const val TAG = "NetworkSpeedMonitor"
        private const val INTERVAL_MS = 1000L
    }

    val rxSpeedKbps: MutableState<Float> = mutableStateOf(0f)
    val txSpeedKbps: MutableState<Float> = mutableStateOf(0f)

    private var monitorJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(gameUid: Int) {
        stop()
        Log.i(TAG, "Starting speed monitor for UID=$gameUid")

        monitorJob = scope.launch {
            var lastRx = TrafficStats.getUidRxBytes(gameUid).takeIf { it >= 0L }
            var lastTx = TrafficStats.getUidTxBytes(gameUid).takeIf { it >= 0L }
            var lastSampleAt = SystemClock.elapsedRealtime()

            while (isActive) {
                delay(INTERVAL_MS)
                try {
                    val currentRx = TrafficStats.getUidRxBytes(gameUid)
                    val currentTx = TrafficStats.getUidTxBytes(gameUid)
                    val sampleAt = SystemClock.elapsedRealtime()

                    if (currentRx >= 0L && currentTx >= 0L) {
                        val elapsedSec = ((sampleAt - lastSampleAt).coerceAtLeast(1L)) / 1000f
                        val previousRx = lastRx
                        val previousTx = lastTx
                        if (previousRx != null && previousTx != null) {
                            rxSpeedKbps.value = ((currentRx - previousRx).coerceAtLeast(0L) / 1024f / elapsedSec)
                            txSpeedKbps.value = ((currentTx - previousTx).coerceAtLeast(0L) / 1024f / elapsedSec)
                        } else {
                            rxSpeedKbps.value = 0f
                            txSpeedKbps.value = 0f
                        }
                        lastRx = currentRx
                        lastTx = currentTx
                        lastSampleAt = sampleAt
                    } else {
                        rxSpeedKbps.value = 0f
                        txSpeedKbps.value = 0f
                        lastRx = null
                        lastTx = null
                        lastSampleAt = sampleAt
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Speed monitor error", e)
                }
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        rxSpeedKbps.value = 0f
        txSpeedKbps.value = 0f
        Log.i(TAG, "Speed monitor stopped")
    }
}
