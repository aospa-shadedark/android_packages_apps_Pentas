/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.tuning

import android.content.Context
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.yasvara.pentas.audio.DolbyBridge
import org.yasvara.pentas.audio.DolbyProfileClient
import org.yasvara.pentas.data.AngleDriver
import org.yasvara.pentas.data.RenderResolutionState
import org.yasvara.pentas.data.encodeRenderScale
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

@Composable fun LiveTag() = Tag("Live")
@Composable fun RestartTag() = Tag("Restart", warn = true)

// ── graphics ──────────────────────────────────────────────────────────────

@Composable
fun GraphicsEditor(pkg: String, game: Boolean = true) {
    val t = rememberTuning()
    val ctx = LocalContext.current
    val perApp = t.perApp()
    val scope = rememberCoroutineScope()
    var resolution by remember(pkg) { mutableStateOf<RenderResolutionState?>(null) }
    var scale by remember(pkg) { mutableFloatStateOf(1f) }
    var applying by remember(pkg) { mutableStateOf(false) }
    var resolutionError by remember(pkg) { mutableStateOf<String?>(null) }
    val screen = remember(ctx) { ctx.safeScreenSize() }
    LaunchedEffect(pkg, game) {
        if (game) {
            val state = withContext(Dispatchers.IO) { perApp.resolutionState(pkg) }
            resolution = state
            scale = state.scale
        }
    }
    fun commit(f: Float) {
        if (resolution?.available != true || applying) return
        applying = true
        scope.launch {
            val (applied, state) = withContext(Dispatchers.IO) {
                perApp.setResolution(pkg, encodeRenderScale(f)) to perApp.resolutionState(pkg)
            }
            resolution = state
            scale = state.scale
            resolutionError = if (applied) null else "Couldn't apply render resolution. Try again."
            applying = false
        }
    }

    if (game) Block(padding = 10.dp) {
        val state = resolution
        SettingRow("Render resolution", tag = {
            when {
                state == null -> Tag("Checking")
                !state.available -> Tag("Unavailable", warn = true)
                applying -> Tag("Applying")
                else -> RestartTag()
            }
        }, value = "%.2f×".format(scale))
        if (state?.available == true) ScaleFigure(scale, screen)
        Slider(min(scale, 1f), { scale = it }, range = 0.25f..1f, steps = 14, onCommit = ::commit,
            description = "Render resolution", enabled = state?.available == true && !applying)
        Text(
            resolutionError ?: state?.unavailableReason
                ?: "Changes game rendering detail, not screen resolution. Fully close and reopen the game to apply.",
            style = label(11.sp),
            color = if (resolutionError == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
    val colorOptions = remember(perApp) { perApp.displayStyleOptions() }
    Block(padding = 10.dp) {
        var style by remember(pkg) { mutableIntStateOf(perApp.displayStyle(pkg)) }
        var colorError by remember(pkg) { mutableStateOf(false) }
        SettingRow("Display color", tag = { if (colorOptions.isEmpty()) Tag("Unavailable") else LiveTag() })
        if (colorOptions.isNotEmpty()) Segmented(colorOptions,
            selected = style, onSelect = {
                colorError = !perApp.setDisplayStyle(pkg, it)
                if (!colorError) style = it
            })
        if (colorError) Text("Couldn't apply display color. Try again.",
            style = label(11.sp), color = MaterialTheme.colorScheme.error)
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

/** Screen and target buffer as nested rectangles, sized to scale. */
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
            Text("Target  ${(w * scale).roundToInt()}×${(h * scale).roundToInt()}", style = num(11.sp, 600), color = cs.onSurface)
        }
    }
}

/** Window metrics can be unavailable in service/overlay contexts; keep the preview safe there. */
private fun Context.safeScreenSize(): Pair<Int, Int> {
    val bounds = runCatching { getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds }
        .getOrNull()
    val width = bounds?.width()?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.coerceAtLeast(1)
    val height = bounds?.height()?.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.coerceAtLeast(1)
    return max(width, height) to min(width, height)
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
    var tuningAvailable by remember(perf) { mutableStateOf(false) }
    if (tuningAvailable) Block(padding = 10.dp) {
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
    if (confirmScope && tuningAvailable) ClockWarningCard(
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
        onAvailabilityChanged = { tuningAvailable = it },
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
    onAvailabilityChanged: (Boolean) -> Unit = {},
    onCommit: (PerfTuner.PerfProfile) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var draftProfile by remember(profile) { mutableStateOf(profile) }
    var showClockConfirmation by remember { mutableStateOf(false) }
    var clockWarningAcknowledged by remember(warningKey) { mutableStateOf(false) }
    var live by remember { mutableStateOf(emptyMap<Int, Long>()) }
    var gpuCap by remember { mutableLongStateOf(0L) }
    var cpuRanges by remember(perf) { mutableStateOf(emptyList<PerfTuner.CpuCluster>()) }
    var gpuInfo by remember(perf) { mutableStateOf<PerfTuner.GpuInfo?>(null) }
    var writableCpu by remember(perf) { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }
    var writableGpu by remember(perf) { mutableStateOf(false) }
    var capabilitiesReady by remember(perf) { mutableStateOf(false) }
    val hasGpuRange = gpuInfo?.availableHz?.size?.let { it > 1 } == true
    val tuningAvailable = capabilitiesReady &&
        (cpuRanges.any { writableCpu[it.policyIndex] == true } || hasGpuRange && writableGpu)
    LaunchedEffect(tuningAvailable) {
        if (!tuningAvailable) {
            draftProfile = profile
            showClockConfirmation = false
        }
        onAvailabilityChanged(tuningAvailable)
    }

    fun applyClockChange(updated: PerfTuner.PerfProfile) {
        draftProfile = updated
        if (!clockWarningAcknowledged) showClockConfirmation = true else onCommit(updated)
    }

    fun cancelClockChange() {
        draftProfile = profile
        showClockConfirmation = false
    }

    LaunchedEffect(perf) {
        while (true) {
            val hardware = withContext(Dispatchers.IO) {
                val cpus = perf.cpuClusters.filter { it.availableKhz.size > 1 }
                Triple(cpus, perf.gpuInfo, cpus.associate { it.policyIndex to perf.canTuneCpuRange(it) } to perf.canTuneGpuRange())
            }
            cpuRanges = hardware.first
            gpuInfo = hardware.second
            writableCpu = hardware.third.first
            writableGpu = hardware.third.second
            capabilitiesReady = true
            delay(15_000)
        }
    }
    LaunchedEffect(perf) {
        while (true) {
            val readings = withContext(Dispatchers.IO) {
                perf.cpuClusters.associate { it.policyIndex to perf.cpuLiveMaxKhz(it) } to perf.gpuLiveMaxHz()
            }
            live = readings.first
            gpuCap = readings.second
            delay(1500)
        }
    }
    if (tuningAvailable && editable) Text(
        "Recommended: keep Default clocks. Changing limits can increase heat, drain, instability or reduce performance. Pentas asks before applying a clock change.",
        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        style = label(11.sp),
        color = cs.onSurfaceVariant,
    )
    if (cpuRanges.isNotEmpty()) Block(padding = 10.dp) {
        cpuRanges.forEach { c ->
            val t = draftProfile.clusters[c.policyIndex] ?: PerfTuner.ClusterTuning()
            val accessGranted = if (capabilitiesReady) writableCpu[c.policyIndex] == true else null
            ClockRange(
                c.name, c.availableKhz, t.minKhz ?: c.hwMinKhz, t.maxKhz ?: c.hwMaxKhz, live[c.policyIndex] ?: 0L,
                editable && accessGranted == true, accessGranted,
                format = { "%.2f".format(it / 1e6) }, unit = "GHz",
            ) { lo, hi ->
                val tuned = t.copy(minKhz = lo.takeIf { it != c.hwMinKhz }, maxKhz = hi.takeIf { it != c.hwMaxKhz })
                applyClockChange(draftProfile.copy(clusters = draftProfile.clusters + (c.policyIndex to tuned)))
            }
        }
    }
    val gpu = gpuInfo
    if (hasGpuRange) Block(padding = 10.dp) {
        val detectedGpu = requireNotNull(gpu)
        ClockRange(
            "GPU", detectedGpu.availableHz, draftProfile.gpuMinHz ?: detectedGpu.hwMinHz,
            draftProfile.gpuMaxHz ?: detectedGpu.hwMaxHz, gpuCap,
            editable && capabilitiesReady && writableGpu,
            if (capabilitiesReady) writableGpu else null,
            format = { "${it / 1_000_000}" }, unit = "MHz",
        ) { lo, hi ->
            applyClockChange(draftProfile.copy(
                gpuMinHz = lo.takeIf { it != detectedGpu.hwMinHz },
                gpuMaxHz = hi.takeIf { it != detectedGpu.hwMaxHz },
            ))
        }
    }
    val unavailableCpu = cpuRanges.any { writableCpu[it.policyIndex] == false }
    if (capabilitiesReady && (unavailableCpu || hasGpuRange && !writableGpu)) Text(
        "Manual clock tuning is unavailable for these controls. Your ROM manages their limits automatically; monitoring still works.",
        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        style = label(11.sp),
        color = cs.onSurfaceVariant,
    )
    if (!capabilitiesReady) Text(
        "Checking CPU and GPU clock support…",
        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        style = label(11.sp),
        color = cs.onSurfaceVariant,
    )
    if (capabilitiesReady && cpuRanges.isEmpty() && !hasGpuRange) Text(
        "No CPU or GPU clock tables were found on this device.",
        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        style = label(11.sp),
        color = cs.onSurfaceVariant,
    )
    if (editable && tuningAvailable && !draftProfile.isDefault) Text(
        "Reset to stock",
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { applyClockChange(PerfTuner.PerfProfile()) }.padding(8.dp),
        style = label(12.sp, 600), color = cs.error,
    )
    if (showClockConfirmation && tuningAvailable) ClockWarningCard(
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
private fun ClockWarningCard(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    Block(color = cs.tertiaryContainer, padding = 12.dp) {
        Text(title, style = label(15.sp, 650), color = cs.onTertiaryContainer)
        Text(message, style = label(12.sp), color = cs.onTertiaryContainer)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                onDismiss()
            }) { Text("Cancel") }
            TextButton(onClick = {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                onConfirm()
            }) { Text(confirmLabel) }
        }
    }
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
    accessGranted: Boolean?,
    format: (Long) -> String,
    unit: String,
    onCommit: (Long, Long) -> Unit,
) {
    if (table.size < 2) return
    if (accessGranted != true) {
        SettingRow(name,
            value = liveCap.takeIf { it > 0L }?.let { "${format(it)} $unit" } ?: "Automatic",
            tag = { Tag(if (accessGranted == null) "Checking" else "Unavailable") })
        return
    }
    val initialRange = remember(name, min, max, table) { clockRangeIndices(table, min, max) }
    var lo by remember(name, min, max, table) { mutableIntStateOf(initialRange.first) }
    var hi by remember(name, min, max, table) { mutableIntStateOf(initialRange.second) }
    val ceiling = liveCap.takeIf { it in 1 until table[hi] }
    val accessTag: (@Composable () -> Unit)? = ceiling?.let { value -> { Tag(format(value), warn = true) } }
    SettingRow(name, value = "${format(table[lo])} – ${format(table[hi])} $unit",
        tag = accessTag)
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

