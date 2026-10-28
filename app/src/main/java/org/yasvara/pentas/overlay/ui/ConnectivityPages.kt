/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.yasvara.pentas.R
import org.yasvara.pentas.overlay.LocalOverlay
import org.yasvara.pentas.ui.kit.Block
import org.yasvara.pentas.ui.kit.Glyph
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.kit.ListItem
import org.yasvara.pentas.ui.kit.Section
import org.yasvara.pentas.ui.kit.SettingRow
import org.yasvara.pentas.ui.kit.Toggle
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num
import org.yasvara.pentas.telemetry.network.PingSource

/** Re-runs [onEvent] whenever one of [actions] is broadcast, while composed. */
@Composable
private fun Broadcasts(vararg actions: String, onEvent: (Intent) -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onEvent(i)
        }
        ctx.registerReceiver(r, IntentFilter().apply { actions.forEach(::addAction) }, Context.RECEIVER_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun BluetoothPage() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val adapter = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    var on by remember { mutableStateOf(adapter?.isEnabled == true) }
    var devices by remember { mutableStateOf(emptyList<BluetoothDevice>()) }
    var tick by remember { mutableIntStateOf(0) }
    fun sync() {
        on = adapter?.isEnabled == true
        devices = runCatching { adapter?.bondedDevices?.toList() }.getOrNull().orEmpty()
            .sortedWith(compareByDescending<BluetoothDevice> { it.isConnected }.thenBy { it.alias ?: it.name })
        tick++
    }
    LaunchedEffect(Unit) { sync() }
    Broadcasts(
        BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothDevice.ACTION_ACL_CONNECTED,
        BluetoothDevice.ACTION_ACL_DISCONNECTED, BluetoothDevice.ACTION_BOND_STATE_CHANGED,
        BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED,
    ) { sync() }
    SubPage("Bluetooth", trailing = {
        Toggle(on, { want -> runCatching { if (want) adapter?.enable() else adapter?.disable() } }, description = "Bluetooth")
    }) {
        if (!on) return@SubPage
        devices.forEachIndexed { i, d ->
            val connected = d.isConnected
            val battery = d.batteryLevel
            ListItem(
                title = d.alias ?: d.name ?: d.address,
                shape = Kit.connected(i, devices.size),
                subtitle = if (connected) "Connected" else null,
                selected = connected,
                leading = { Glyph(R.drawable.sym_bluetooth, if (connected) cs.onSecondaryContainer else cs.onSurfaceVariant, 18.dp) },
                trailing = if (connected && battery in 0..100) ({
                    Text("$battery%", style = num(11.sp, 600), color = cs.onSecondaryContainer)
                }) else null,
                onClick = { runCatching { if (connected) d.disconnect() else d.connect() } },
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun WifiPage() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val wifi = remember { ctx.getSystemService(WifiManager::class.java) }
    var on by remember { mutableStateOf(wifi.isWifiEnabled) }
    var rev by remember { mutableIntStateOf(0) }
    Broadcasts(
        WifiManager.WIFI_STATE_CHANGED_ACTION, WifiManager.SCAN_RESULTS_AVAILABLE_ACTION,
        WifiManager.NETWORK_STATE_CHANGED_ACTION,
    ) { on = wifi.isWifiEnabled; rev++ }
    LaunchedEffect(on) { if (on) runCatching { wifi.startScan() } }
    SubPage("Wi-Fi", trailing = { Toggle(on, { wifi.setWifiEnabled(it) }, description = "Wi-Fi") }) {
        if (!on) return@SubPage
        @Suppress("DEPRECATION") val info = remember(rev) { wifi.connectionInfo }
        val current = info?.ssid?.trim('"')?.takeIf { info.networkId != -1 }
        if (current != null) Block(padding = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Glyph(R.drawable.sym_wifi, cs.primary, 18.dp)
                Text(current, style = label(13.sp, 600), color = cs.onSurface)
            }
            SettingRow("Signal", value = "${info.rssi} dBm")
            SettingRow("Link", value = "${info.linkSpeed} Mbps · %.1f GHz".format(info.frequency / 1000f))
        }
        @Suppress("DEPRECATION") val saved = remember(rev) {
            runCatching { wifi.configuredNetworks }.getOrNull().orEmpty().associateBy { it.SSID.trim('"') }
        }
        val nearby = remember(rev) {
            wifi.scanResults.filter { it.SSID.isNotEmpty() && it.SSID != current }
                .groupBy { it.SSID }.map { (_, v) -> v.maxBy { it.level } }
                .sortedWith(compareByDescending<android.net.wifi.ScanResult> { it.SSID in saved }.thenByDescending { it.level })
        }
        if (nearby.isNotEmpty()) Section("Nearby")
        nearby.forEachIndexed { i, r ->
            val config = saved[r.SSID]
            ListItem(
                title = r.SSID,
                shape = Kit.connected(i, nearby.size),
                subtitle = if (config != null) "Saved" else null,
                leading = { Glyph(R.drawable.sym_wifi, if (config != null) cs.onSurface else cs.onSurfaceVariant, 18.dp) },
                trailing = { Text("${WifiManager.calculateSignalLevel(r.level, 5)}/4", style = num(11.sp, 500), color = cs.onSurfaceVariant) },
                onClick = config?.let { c -> { wifi.connect(c.networkId, null) } },
            )
        }
    }
}

@Composable
fun PingPage() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); refresh++ } }
    val servers = remember(refresh) { o.network.allServerPings.value }
    val source = o.network.pingSource.value
    SubPage("Network") {
        Block(padding = 10.dp) {
            SettingRow("Best RTT", value = o.network.latencyMs.value.takeIf { it > 0 }?.let { "$it ms" } ?: "—")
            SettingRow("Down", value = "%.0f KB/s".format(o.network.rxSpeedKbps.value))
            SettingRow("Up", value = "%.0f KB/s".format(o.network.txSpeedKbps.value))
        }
        val sourceNote = when (source) {
            PingSource.GAME_ENDPOINTS -> "Estimated endpoint RTT using ICMP or a TCP connect; this may differ from the game's own ping."
            PingSource.NETWORK_ROUTE -> "Fallback to the active network's DNS server or gateway; this is not game-server latency."
            PingSource.NONE -> "Waiting for a measurable endpoint from this game."
        }
        Text(sourceNote, style = label(11.5.sp), color = cs.onSurfaceVariant)
        if (servers.isNotEmpty()) Section(
            if (source == PingSource.NETWORK_ROUTE) "Network route · DNS / gateway" else "Game endpoints"
        )
        servers.forEachIndexed { i, (ip, ms) ->
            ListItem(
                title = ip, shape = Kit.connected(i, servers.size),
                trailing = { Text(if (ms > 0) "$ms ms" else "—", style = num(12.sp, 600), color = if (ms >= 120) cs.error else cs.onSurface) },
            )
        }
    }
}
