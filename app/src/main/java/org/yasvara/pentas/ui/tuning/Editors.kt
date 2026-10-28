/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.tuning

import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.yasvara.pentas.audio.DolbyBridge
import org.yasvara.pentas.audio.DolbyProfileClient
import org.yasvara.pentas.data.AngleDriver
import org.yasvara.pentas.thermal.PerfTuner
import org.yasvara.pentas.touch.KernelTouchFilterClient
import org.yasvara.pentas.touch.RateMeter
import org.yasvara.pentas.ui.kit.Block
import org.yasvara.pentas.ui.kit.RangeSlider
import org.yasvara.pentas.ui.kit.Section
import org.yasvara.pentas.ui.kit.Segmented
import org.yasvara.pentas.ui.kit.SettingRow
import org.yasvara.pentas.ui.kit.Slider
import org.yasvara.pentas.ui.kit.SwitchRow
import org.yasvara.pentas.ui.kit.Tag
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun factor(f: Float): String =
    if (abs(f - 1f) < 0.001f) "1.0" else "%.2f".format(f).trimEnd('0').trimEnd('.').let { if ('.' in it) it else "$it.0" }

@Composable fun LiveTag() = Tag("Live")
@Composable fun RestartTag() = Tag("Restart", warn = true)

// ── graphics ──────────────────────────────────────────────────────────────

@Composable
fun GraphicsEditor(pkg: String, game: Boolean = true) {
    val t = rememberTuning()
    val ctx = LocalContext.current
    var scale by remember(pkg) { mutableFloatStateOf(t.perApp().resolution(pkg).toFloatOrNull() ?: 1f) }
    val screen = remember {
        val b = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        max(b.width(), b.height()) to min(b.width(), b.height())
    }
    fun commit(f: Float) { scale = f; t.perApp().setResolution(pkg, factor(f)) }

    if (game) Block(padding = 10.dp) {
        SettingRow("Render resolution", tag = { RestartTag() }, value = "%.2f×".format(scale))
        ScaleFigure(scale, screen)
        Slider(min(scale, 1f), { scale = it }, range = 0.25f..1f, steps = 14, onCommit = ::commit,
            description = "Render resolution")
    }
    Block(padding = 10.dp) {
        var style by remember(pkg) { mutableIntStateOf(t.perApp().displayStyle(pkg)) }
        SettingRow("Display color", tag = { LiveTag() })
        Segmented(
            listOf("Original" to 0, "Vivid" to 1, "Saturated" to 2, "P3" to 3, "sRGB" to 4),
            selected = style, onSelect = { style = it; t.perApp().setDisplayStyle(pkg, it) },
        )
    }
    if (!game) return
    Section("Advanced")
    Block(padding = 10.dp) {
        val angle = t.angle()
        if (angle.available) {
            var api by remember(pkg) { mutableStateOf(angle.get(pkg)) }
            SettingRow("GLES driver", tag = { RestartTag() })
            Segmented(
                listOf("Default" to AngleDriver.DEFAULT, "ANGLE" to AngleDriver.ANGLE, "Native" to AngleDriver.NATIVE),
                selected = api, onSelect = { api = it; angle.set(pkg, it) },
            )
        }
        var gpuComp by remember(pkg) { mutableStateOf(t.perApp().gpuComposition(pkg)) }
        SwitchRow("GPU composition", gpuComp, { gpuComp = it; t.perApp().setGpuComposition(pkg, it) }, tag = { LiveTag() })
    }
}

/** Screen and drawn buffer as nested rectangles, sized to scale. */
@Composable
private fun ScaleFigure(scale: Float, screen: Pair<Int, Int>) {
    val cs = MaterialTheme.colorScheme
    val (w, h) = screen
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.height(54.dp).fillMaxWidth(0.42f).clip(RoundedCornerShape(6.dp))
            .border(1.5.dp, cs.outline, RoundedCornerShape(6.dp)), contentAlignment = Alignment.TopStart) {
            val s = scale.coerceAtMost(1f)
            Box(Modifier.fillMaxWidth(s).fillMaxHeight(s).clip(RoundedCornerShape(4.dp)).background(cs.primary.copy(alpha = 0.55f)))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Screen  ${w}×$h", style = num(11.sp, 500), color = cs.onSurfaceVariant)
            Text("Drawn  ${(w * scale).roundToInt()}×${(h * scale).roundToInt()}", style = num(11.sp, 600), color = cs.onSurface)
        }
    }
}

// ── clocks ────────────────────────────────────────────────────────────────

/** Per-game clocks: own limits or the global baseline. */
@Composable
fun GameClocks(pkg: String) {
    val perf = rememberTuning().perf()
    var scope by remember(pkg) { mutableIntStateOf(perf.clockScope(pkg)) }
    var profile by remember(pkg) { mutableStateOf(perf.loadProfile(pkg) ?: PerfTuner.PerfProfile()) }
    var pendingScope by remember(pkg) { mutableIntStateOf(scope) }
    var confirmScope by remember(pkg) { mutableStateOf(false) }
    Block(padding = 10.dp) {
        Segmented(
            listOf("This game" to PerfTuner.ClockScope.PER_APP, "Global" to PerfTuner.ClockScope.GLOBAL),
            selected = scope, onSelect = {
                if (scope != it) {
                    pendingScope = it
                    confirmScope = true
                } else {
                    scope = it
                    perf.setClockScope(pkg, it)
                }
            },
        )
    }
    if (confirmScope) ClockWarningDialog(
        title = if (pendingScope == PerfTuner.ClockScope.GLOBAL) "Use global clocks for this game?" else "Use this game's clocks?",
        message = if (pendingScope == PerfTuner.ClockScope.GLOBAL) {
            "This game will follow the global CPU and GPU limits. Higher limits can increase heat and battery drain, and thermal control may still reduce performance."
        } else {
            "This game will stop following global limits and use its saved CPU and GPU settings. They can increase heat and battery drain or reduce stability and performance."
        },
        confirmLabel = if (pendingScope == PerfTuner.ClockScope.GLOBAL) "Use global" else "Use game limits",
        onConfirm = {
            scope = pendingScope
            perf.setClockScope(pkg, pendingScope)
            confirmScope = false
        },
        onDismiss = { confirmScope = false },
    )
    val global = scope == PerfTuner.ClockScope.GLOBAL
    ClockEditor(
        perf, if (global) perf.loadGlobalProfile() else profile, editable = !global,
        warningKey = pkg,
        onCommit = { profile = it; perf.saveProfileFor(pkg, it) },
    )
}

/** Min/max clock per CPU cluster and GPU, snapped to the kernel tables. */
@Composable
fun ClockEditor(
    perf: PerfTuner,
    profile: PerfTuner.PerfProfile,
    editable: Boolean = true,
    warningKey: Any = profile,
    onCommit: (PerfTuner.PerfProfile) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var draftProfile by remember(profile) { mutableStateOf(profile) }
    var showClockConfirmation by remember { mutableStateOf(false) }
    var clockWarningAcknowledged by remember(warningKey) { mutableStateOf(false) }
    var live by remember { mutableStateOf(emptyMap<Int, Long>()) }
    var gpuCap by remember { mutableLongStateOf(0L) }

    fun applyClockChange(updated: PerfTuner.PerfProfile) {
        draftProfile = updated
        if (!clockWarningAcknowledged) showClockConfirmation = true else onCommit(updated)
    }

    fun cancelClockChange() {
        draftProfile = profile
        showClockConfirmation = false
    }

    LaunchedEffect(Unit) {
        while (true) {
            live = perf.cpuClusters.associate { it.policyIndex to perf.cpuLiveMaxKhz(it) }
            gpuCap = perf.gpuLiveMaxHz()
            delay(1500)
        }
    }
    Text(
        "Recommended: keep Default clocks. Changing limits can increase heat, drain, instability or reduce performance. Pentas asks before applying a clock change.",
        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        style = label(11.sp),
        color = cs.onSurfaceVariant,
    )
    Block(padding = 10.dp) {
        perf.cpuClusters.filter { it.availableKhz.size > 1 }.forEach { c ->
            val t = draftProfile.clusters[c.policyIndex] ?: PerfTuner.ClusterTuning()
            ClockRange(
                c.name, c.availableKhz, t.minKhz ?: c.hwMinKhz, t.maxKhz ?: c.hwMaxKhz, live[c.policyIndex] ?: 0L,
                editable, format = { "%.2f".format(it / 1e6) }, unit = "GHz",
            ) { lo, hi ->
                val tuned = t.copy(minKhz = lo.takeIf { it != c.hwMinKhz }, maxKhz = hi.takeIf { it != c.hwMaxKhz })
                applyClockChange(draftProfile.copy(clusters = draftProfile.clusters + (c.policyIndex to tuned)))
            }
        }
    }
    val gpu = perf.gpuInfo
    if (gpu.availableHz.size > 1) Block(padding = 10.dp) {
        ClockRange(
            "GPU", gpu.availableHz.sorted(), draftProfile.gpuMinHz ?: gpu.hwMinHz, draftProfile.gpuMaxHz ?: gpu.hwMaxHz, gpuCap,
            editable, format = { "${it / 1_000_000}" }, unit = "MHz",
        ) { lo, hi ->
            applyClockChange(draftProfile.copy(gpuMinHz = lo.takeIf { it != gpu.hwMinHz }, gpuMaxHz = hi.takeIf { it != gpu.hwMaxHz }))
        }
    }
    if (editable && !draftProfile.isDefault) Text(
        "Reset to stock",
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { applyClockChange(PerfTuner.PerfProfile()) }.padding(8.dp),
        style = label(12.sp, 600), color = cs.error,
    )
    if (showClockConfirmation) ClockWarningDialog(
        title = "Apply clock limits?",
        message = "CPU or GPU limits can raise heat and battery drain, reduce stability or lower performance. Keep Default unless you have a specific reason to tune them.",
        confirmLabel = "Apply limits",
        onConfirm = {
            clockWarningAcknowledged = true
            showClockConfirmation = false
            onCommit(draftProfile)
        },
        onDismiss = ::cancelClockChange,
    )
}

@Composable
private fun ClockWarningDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One clock domain: floor and ceiling over [table]; a tag shows a lower live ceiling. */
@Composable
private fun ClockRange(
    name: String,
    table: List<Long>,
    min: Long,
    max: Long,
    liveCap: Long,
    editable: Boolean,
    format: (Long) -> String,
    unit: String,
    onCommit: (Long, Long) -> Unit,
) {
    var lo by remember(name, min) { mutableIntStateOf(table.indexOfNearest(min)) }
    var hi by remember(name, max) { mutableIntStateOf(table.indexOfNearest(max)) }
    val ceiling = liveCap.takeIf { it in 1 until table[hi] }
    SettingRow(name, value = "${format(table[lo])} – ${format(table[hi])} $unit",
        tag = ceiling?.let { { Tag(format(it), warn = true) } })
    RangeSlider(
        lo, hi, table.size,
        onChange = { a, b -> if (editable) { lo = a; hi = b } },
        onCommit = { a, b -> if (editable) onCommit(table[a], table[b]) },
        marker = ceiling?.let { table.indexOfNearest(it) },
        enabled = editable,
        description = name,
        valueText = { "${format(table[it])} $unit" },
    )
}

private fun List<Long>.indexOfNearest(v: Long): Int = indices.minByOrNull { abs(this[it] - v) } ?: 0

// ── touch ─────────────────────────────────────────────────────────────────

@Composable
fun TouchEditor(pkg: String, pad: Boolean) {
    val touch = rememberTuning().touch()
    if (pad) Block(padding = 6.dp) { TouchPad() }
    if (touch.supportsSuperReport) Block(padding = 10.dp) {
        var on by remember(pkg) { mutableStateOf(touch.superReport(pkg)) }
        LaunchedEffect(on) { touch.refreshReportRate() }
        SwitchRow("Super touch", on, { on = it; touch.setSuperReport(pkg, it) }, tag = { LiveTag() })
        SettingRow("Scan rate", value = "${touch.reportRateHz.intValue} Hz")
    }
    if (touch.supportsAccidentalTouchFilter) Block(padding = 10.dp) {
        val levels = listOf("Off" to KernelTouchFilterClient.LEVEL_OFF, "Low" to KernelTouchFilterClient.LEVEL_LOW,
            "Mid" to KernelTouchFilterClient.LEVEL_MEDIUM, "High" to KernelTouchFilterClient.LEVEL_HIGH)
        var edge by remember(pkg) { mutableIntStateOf(touch.edgeFilter(pkg)) }
        var grip by remember(pkg) { mutableIntStateOf(touch.gripSuppression(pkg)) }
        SettingRow("Edge filter")
        Segmented(levels, edge, { edge = it; touch.setEdgeFilter(pkg, it) })
        SettingRow("Grip suppression")
        Segmented(levels, grip, { grip = it; touch.setGripSuppression(pkg, it) })
    }
}

/** Draw here to read the panel's real sample rate, history samples included. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TouchPad() {
    val cs = MaterialTheme.colorScheme
    val meter = remember { RateMeter() }
    var hz by remember { mutableIntStateOf(0) }
    var peak by remember { mutableIntStateOf(0) }
    val points = remember { mutableStateListOf<Offset>() }
    Box(
        Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHigh)
            .pointerInteropFilter { e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { meter.reset(); points.clear() }
                    MotionEvent.ACTION_MOVE -> {
                        for (h in 0 until e.historySize) meter.addSample(e.getHistoricalEventTime(h))
                        meter.addSample(e.eventTime)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> meter.stop()
                }
                meter.update(e.eventTime)
                hz = meter.instantHz; peak = meter.peakHz
                points.clear()
                for (i in 0 until e.pointerCount) points += Offset(e.getX(i), e.getY(i))
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) { points.forEach { drawCircle(cs.primary.copy(alpha = 0.5f), 18.dp.toPx(), it) } }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (hz > 0) "$hz" else "—", style = num(28.sp, 620), color = cs.onSurface)
            Text(if (peak > 0) "Hz · peak $peak" else "Hz", style = label(11.sp), color = cs.onSurfaceVariant)
        }
    }
}

// ── sound ─────────────────────────────────────────────────────────────────

@Composable
fun SoundEditor(pkg: String?) {
    val ctx = LocalContext.current
    val dolby = rememberTuning().dolby()
    val st = dolby.state
    if (!st.available) return
    Block(padding = 10.dp) {
        SwitchRow("Dolby Atmos", st.enabled, dolby::setEnabled)
        Segmented(
            listOf("Dynamic" to DolbyBridge.PROFILE_DYNAMIC, "Movie" to DolbyBridge.PROFILE_MOVIE,
                "Music" to DolbyBridge.PROFILE_MUSIC, "Game" to DolbyBridge.PROFILE_GAME),
            selected = st.profile, onSelect = dolby::setProfile,
        )
    }
    if (pkg == null) return
    Block(padding = 10.dp) {
        var mine by remember(pkg) { mutableIntStateOf(DolbyProfileClient.getProfile(ctx, pkg)) }
        SettingRow("This game")
        Segmented(
            listOf("Global" to DolbyProfileClient.PROFILE_DEFAULT) +
                (0..3).map { DolbyProfileClient.PROFILE_NAMES[it] to it },
            selected = mine,
            onSelect = {
                mine = it
                DolbyProfileClient.setProfile(ctx, pkg, it)
                if (it >= 0) dolby.setProfile(it)
            },
        )
    }
}

