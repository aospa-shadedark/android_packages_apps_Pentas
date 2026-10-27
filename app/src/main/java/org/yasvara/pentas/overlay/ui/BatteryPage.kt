/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.yasvara.pentas.device.DeviceSupport
import org.yasvara.pentas.thermal.KernelNodeIO
import org.yasvara.pentas.thermal.ThermalNodes
import org.yasvara.pentas.ui.kit.Block
import org.yasvara.pentas.ui.kit.SettingRow
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num
import java.io.File
import kotlin.math.abs

private data class Pack(
    val capacity: Int,
    val currentUa: Long,
    val voltageUv: Long,
    val cellC: Float,
    val boardC: Float,
    val full: Long,
    val design: Long,
    val cycles: Int,
    val adapter: String?,
    val bypassMode: Int,
    val bypassState: Int,
    val bypassDrainMa: Int,
) {
    val watts get() = abs(currentUa / 1e6f * voltageUv / 1e6f)
    val charging get() = currentUa > 0
    val health get() = if (design > 0) full * 100f / design else 0f
}

private fun uevent(dir: String): Map<String, String> = runCatching {
    File("/sys/class/power_supply/$dir/uevent").readLines().mapNotNull { l ->
        l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).removePrefix("POWER_SUPPLY_") to l.substring(it + 1) }
    }.toMap()
}.getOrDefault(emptyMap())

/** The gauge reports "unsupported" as a large negative sentinel; reject out-of-band values. */
private fun Map<String, String>.num(k: String, sane: LongRange) = this[k]?.trim()?.toLongOrNull()?.takeIf { it in sane }

private fun readPack(): Pack {
    val b = uevent("battery")
    val u = uevent("usb")
    val supportsBypass = DeviceSupport.bypassCharging
    val state = if (supportsBypass) {
        KernelNodeIO.read(ThermalNodes.BYPASS_STATE)?.trim()?.split(' ')?.mapNotNull { it.toIntOrNull() }.orEmpty()
    } else emptyList()
    return Pack(
        capacity = b["CAPACITY"]?.trim()?.toIntOrNull() ?: 0,
        currentUa = -(b.num("CURRENT_NOW", -60_000_000L..60_000_000L) ?: 0L),
        voltageUv = b.num("VOLTAGE_NOW", 0L..6_000_000L) ?: 0L,
        cellC = (b.num("TEMP", -500L..1500L) ?: 0L) / 10f,
        boardC = (KernelNodeIO.readLong(ThermalNodes.BATT_THERM_ZONE) ?: 0L) / 1000f,
        full = b.num("CHARGE_FULL", 0L..20_000_000L) ?: 0L,
        design = b.num("CHARGE_FULL_DESIGN", 0L..20_000_000L) ?: 0L,
        cycles = b["CYCLE_COUNT"]?.trim()?.toIntOrNull() ?: 0,
        adapter = if (u["ONLINE"]?.trim() == "1") "%s · %.1f V".format(u["TYPE"] ?: "USB", (u.num("VOLTAGE_NOW", 0L..25_000_000L) ?: 0L) / 1e6) else null,
        bypassMode = if (supportsBypass) KernelNodeIO.readInt(ThermalNodes.BYPASS_CHARGE) ?: 0 else 0,
        bypassState = state.getOrElse(0) { 0 },
        bypassDrainMa = state.getOrElse(1) { 0 },
    )
}

@Composable
fun BatteryPage() {
    val cs = MaterialTheme.colorScheme
    var pack by remember { mutableStateOf<Pack?>(null) }
    var flow by remember { mutableStateOf(listOf<Float>()) }
    LaunchedEffect(Unit) {
        while (true) {
            val p = withContext(Dispatchers.IO) { readPack() }
            pack = p
            flow = (flow + if (p.charging) p.watts else -p.watts).takeLast(90)
            delay(1000)
        }
    }
    SubPage("Battery") {
        val p = pack ?: return@SubPage
        Block(padding = 12.dp) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${p.capacity}%", style = num(30.sp, 620), color = if (p.charging) cs.primary else cs.onSurface)
                Column(Modifier.padding(bottom = 4.dp)) {
                    Text("%s%.2f W".format(if (abs(p.currentUa) < 60_000) "" else if (p.charging) "+" else "−", p.watts),
                        style = num(13.sp, 600), color = cs.onSurface)
                    Text("%.2f A · %.2f V".format(abs(p.currentUa) / 1e6, p.voltageUv / 1e6), style = num(11.sp, 500), color = cs.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip("Cell", "%.1f°".format(p.cellC), p.cellC >= 42f, Modifier.weight(1f))
                Chip("Board", "%.1f°".format(p.boardC), false, Modifier.weight(1f))
                Chip("Health", "%.0f%%".format(p.health), p.health in 0.1f..80f, Modifier.weight(1f))
            }
        }
        Block(padding = 10.dp) {
            PowerFlow(flow)
            p.adapter?.let { SettingRow("Adapter", value = it) }
        }
        if (DeviceSupport.bypassCharging && p.bypassMode != ThermalNodes.BYPASS_MODE_OFF) Block(padding = 10.dp) {
            SettingRow("Bypass", value = when (p.bypassMode) { ThermalNodes.BYPASS_MODE_FORCE -> "Always"; else -> "Auto" })
            SettingRow("State", value = when (p.bypassState) {
                ThermalNodes.BYPASS_ST_ACTIVE -> "Active"
                ThermalNodes.BYPASS_ST_ASSIST -> "Assist"
                4 -> "Full"
                else -> "Idle"
            })
            SettingRow("Pack", value = if (p.bypassDrainMa > 0) "−${p.bypassDrainMa} mA" else "+${-p.bypassDrainMa} mA")
        }
        Block(padding = 10.dp) {
            SettingRow("Capacity", value = "%.0f / %.0f mAh".format(p.full / 1000.0, p.design / 1000.0))
            SettingRow("Cycles", value = "${p.cycles}")
        }
    }
}

@Composable
private fun Chip(name: String, value: String, warn: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(if (warn) cs.errorContainer else cs.surfaceContainerHigh)
            .padding(vertical = 6.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text(value, style = num(13.sp, 620), color = if (warn) cs.onErrorContainer else cs.onSurface)
        Text(name, style = label(10.sp), color = cs.onSurfaceVariant)
    }
}

/** Power into (+) or out of (−) the pack over the last 90 s; the centre line is 0 W. */
@Composable
private fun PowerFlow(history: List<Float>) {
    val cs = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val mid = size.height / 2
        drawLine(cs.outlineVariant, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        if (history.size < 2) return@Canvas
        val peak = history.maxOf { abs(it) }.coerceAtLeast(0.5f)
        val dx = size.width / (history.size - 1)
        val path = Path()
        history.forEachIndexed { i, v ->
            val y = mid - v / peak * (mid - 3.dp.toPx())
            if (i == 0) path.moveTo(0f, y) else path.lineTo(i * dx, y)
        }
        drawPath(path, if (history.last() >= 0) cs.primary else cs.tertiary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}
