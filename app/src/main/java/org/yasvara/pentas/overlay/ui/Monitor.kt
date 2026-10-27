/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.RemeasureToBounds
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.yasvara.pentas.R
import org.yasvara.pentas.overlay.LocalOverlay
import org.yasvara.pentas.overlay.Metric
import org.yasvara.pentas.overlay.Page
import org.yasvara.pentas.overlay.monitor.AppMemory
import org.yasvara.pentas.overlay.monitor.AppRow
import org.yasvara.pentas.overlay.monitor.HOT_SOC
import org.yasvara.pentas.overlay.monitor.ICON_PX
import org.yasvara.pentas.overlay.monitor.ProcessRow
import org.yasvara.pentas.overlay.monitor.RunningApps
import org.yasvara.pentas.overlay.monitor.Widget
import org.yasvara.pentas.telemetry.ClusterSnap
import org.yasvara.pentas.telemetry.PanelRates
import org.yasvara.pentas.telemetry.TelemetrySnapshot
import org.yasvara.pentas.ui.kit.Block
import org.yasvara.pentas.ui.kit.Glyph
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.kit.RoundButton
import org.yasvara.pentas.ui.kit.Segmented
import org.yasvara.pentas.ui.kit.SettingRow
import org.yasvara.pentas.ui.kit.Slider
import org.yasvara.pentas.ui.kit.Tag
import org.yasvara.pentas.ui.kit.Toggle
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num
import org.yasvara.pentas.ui.kit.segmentShape
import org.yasvara.pentas.utils.rememberAppIconCache

private val FadeIn = fadeIn(spring(stiffness = 1600f))
private val FadeOut = fadeOut(spring(stiffness = 3800f))
private val Morph = BoundsTransform { _, _ -> Kit.default() }
private const val TOP_APPS = 20

/**
 * The game home's live tiles. A tapped tile opens in place into its card and the others fold
 * into tabs; tapping the open tab folds it back, another tab switches.
 */
@Composable
fun Monitor(
    modifier: Modifier,
    scrollControls: Boolean = true,
    controls: @Composable ColumnScope.() -> Unit,
) {
    val open by LocalOverlay.current.state.metric
    SharedTransitionLayout(modifier) {
        AnimatedContent(open, transitionSpec = { FadeIn togetherWith FadeOut }, contentKey = { it != null }, label = "metric") { m ->
            // Tile i morphs into tab i, or into the card when it is the open one.
            val morph = Metric.entries.map {
                Modifier.sharedBounds(
                    rememberSharedContentState(it), this@AnimatedContent,
                    enter = FadeIn, exit = FadeOut, boundsTransform = Morph, resizeMode = RemeasureToBounds,
                )
            }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Kit.Gap)) {
                if (m == null) {
                    Tiles(morph)
                    val controlsModifier = Modifier.weight(1f)
                        .then(if (scrollControls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .animateEnterExit(
                            enter = slideInVertically(Kit.default()) { it / 4 },
                            exit = slideOutVertically(Kit.default()) { it / 4 },
                        )
                    Column(controlsModifier, verticalArrangement = Arrangement.spacedBy(Kit.Gap), content = controls)
                } else {
                    Tabs(m, morph)
                    Card(m, Modifier.weight(1f).then(morph[m.ordinal]), Modifier.skipToLookaheadSize())
                }
            }
        }
    }
}

/** The panel's rates, followed while the caller is on screen. */
@Composable
private fun rememberPanelRates(): PanelRates {
    val fps = LocalOverlay.current.fps
    return fps.panel.collectAsState(remember { fps.panelRates() }).value
}

/** A metric's live value and unit, shown by its tile and its tab. */
private fun headline(m: Metric, s: TelemetrySnapshot, fps: Float): Pair<String, String> = when (m) {
    Metric.FPS -> "${fps.toInt()}" to ""
    Metric.CPU -> "%.1f".format(s.cpuFreqMaxKhz / 1e6f) to "GHz"
    Metric.GPU -> "${s.gpuFreqMhz}" to "MHz"
    Metric.RAM -> "%.1f".format(s.ramUsedGb) to "/ ${ramGb(s)}"
}

private fun ramGb(s: TelemetrySnapshot) = ceil(s.ramTotalGb).toInt()
private fun ghz(khz: Long) = "%.2f".format(khz / 1e6f)
private fun mem(kb: Long) = if (kb >= 1 shl 20) "%.1f GB".format(kb / 1_048_576f) else "${kb / 1024} MB"

// ── tiles ─────────────────────────────────────────────────────────────────

@Composable
private fun RowScope.Tile(m: Metric, morph: List<Modifier>, content: @Composable () -> Unit) {
    val o = LocalOverlay.current
    Column(
        Modifier.weight(1f).then(morph[m.ordinal]).height(86.dp).clip(RoundedCornerShape(Kit.Card))
            .background(MaterialTheme.colorScheme.surfaceContainer).clickable { o.state.toggle(m) }
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 7.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) { content() }
}

@Composable
private fun TileLabel(text: String, value: String? = null, warn: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = label(10.5.sp), color = cs.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        value?.let { Text(it, style = num(10.5.sp, 560), color = if (warn) cs.error else cs.onSurfaceVariant) }
    }
}

@Composable
private fun TileValue(
    v: Pair<String, String>,
    size: TextUnit = 16.sp,
    color: Color = MaterialTheme.colorScheme.onSurface,
    unit: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(v.first, style = num(size, 620), color = color)
        if (v.second.isNotEmpty()) Text(" ${v.second}", style = label(10.sp), color = unit)
    }
}

/** Rising steps, lit up to the live value; steps above the live ceiling are dimmed. */
@Composable
private fun Ladder(n: Int, height: (Int) -> Float, lit: (Int) -> Boolean, capped: (Int) -> Boolean, modifier: Modifier, gap: Dp) {
    val cs = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom) {
        repeat(n) { i ->
            Box(
                Modifier.weight(1f).fillMaxHeight(height(i)).clip(RoundedCornerShape(gap)).background(when {
                    capped(i) -> cs.surfaceContainerHighest.copy(alpha = 0.4f)
                    lit(i) -> cs.primary
                    else -> cs.surfaceContainerHighest
                })
            )
        }
    }
}

@Composable
private fun GpuLadder(s: TelemetrySnapshot, modifier: Modifier, gap: Dp) {
    val steps = LocalOverlay.current.perf.gpuInfo.availableHz
    Ladder(
        steps.size, { 0.15f + 0.85f * (it + 1) / steps.size },
        lit = { steps[it] / 1_000_000 <= s.gpuFreqMhz }, capped = { s.gpuCapHz > 0 && steps[it] > s.gpuCapHz },
        modifier = modifier, gap = gap,
    )
}

@Composable
private fun Tiles(morph: List<Modifier>) {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val s by o.bus.snapshot.collectAsState()
    val fps by o.fps.realFpsHistory.collectAsState()
    val panel = rememberPanelRates()
    val now = fps.lastOrNull() ?: 0f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // FPS: the rate against the panel's refresh steps; steps above the live panel rate are dimmed.
        Tile(Metric.FPS, morph) {
            TileLabel("FPS", "${panel.hz} Hz")
            Text("${now.toInt()}", style = num(22.sp, 620), color = cs.primary)
            val rates = panel.ladder
            val top = rates.lastOrNull() ?: 1
            Ladder(
                rates.size, { rates[it].toFloat() / top },
                lit = { now >= rates[it] - 2 }, capped = { rates[it] > panel.hz },
                modifier = Modifier.fillMaxWidth().height(8.dp), gap = 2.dp,
            )
        }
        // CPU: one bar per cluster, track sized by its max clock, fill by the live clock.
        Tile(Metric.CPU, morph) {
            TileLabel("CPU", "%.0f°".format(s.cpuTempC), s.cpuTempC >= HOT_SOC)
            val top = s.cpuClusters.maxOfOrNull { it.hwMaxKhz }?.coerceAtLeast(1) ?: 1
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                s.cpuClusters.forEach { c ->
                    Box(Modifier.fillMaxWidth(c.hwMaxKhz.toFloat() / top).height(4.dp).clip(CircleShape).background(cs.surfaceContainerHighest)) {
                        Box(
                            Modifier.fillMaxHeight().fillMaxWidth((c.curKhz.toFloat() / c.hwMaxKhz.coerceAtLeast(1)).coerceIn(0f, 1f))
                                .clip(CircleShape).background(if (c.scalingMaxKhz < c.hwMaxKhz) cs.tertiary else cs.primary)
                        )
                    }
                }
            }
            TileValue(headline(Metric.CPU, s, now))
        }
        // GPU: the frequency steps as a ladder; steps above the live cap are dimmed.
        Tile(Metric.GPU, morph) {
            TileLabel("GPU", "${s.gpuUsage}%")
            GpuLadder(s, Modifier.fillMaxWidth().height(26.dp), 1.5.dp)
            TileValue(headline(Metric.GPU, s, now))
        }
        // RAM: one cell per GB.
        Tile(Metric.RAM, morph) {
            TileLabel("RAM")
            Row(Modifier.fillMaxWidth().height(9.dp), horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
                repeat(ramGb(s).coerceIn(1, 24)) { i ->
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                        .background(if (i < s.ramUsedGb) cs.primary else cs.surfaceContainerHighest))
                }
            }
            TileValue(headline(Metric.RAM, s, now))
        }
    }
}

/** The tiles folded into a connected tab row; the open one is a filled pill. */
@Composable
private fun Tabs(open: Metric, morph: List<Modifier>) {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val s by o.bus.snapshot.collectAsState()
    val fps by o.fps.realFpsHistory.collectAsState()
    val now = fps.lastOrNull() ?: 0f
    Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Metric.entries.forEach { m ->
            val on = m == open
            val bg by animateColorAsState(if (on) cs.primary else cs.surfaceContainer, label = "tab")
            val dim = if (on) cs.onPrimary else cs.onSurfaceVariant
            Column(
                Modifier.weight(1f).then(if (on) Modifier else morph[m.ordinal]).fillMaxHeight()
                    .clip(segmentShape(m.ordinal, Metric.entries.size, on, 20.dp)).background(bg)
                    .selectable(on, role = Role.Tab) { o.state.toggle(m) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(m.name, style = label(10.sp, 600), color = dim)
                TileValue(headline(m, s, now), 14.sp, if (on) cs.onPrimary else cs.onSurface, dim)
            }
        }
    }
}

// ── cards ─────────────────────────────────────────────────────────────────

/** The open metric's card; switching tabs slides between cards in tab order. */
@Composable
private fun Card(open: Metric, modifier: Modifier, inner: Modifier) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(Kit.Card)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        AnimatedContent(
            open, inner.fillMaxSize(),
            transitionSpec = {
                val d = if (targetState > initialState) 1 else -1
                (FadeIn + slideInHorizontally(Kit.default()) { d * it / 6 }) togetherWith
                    (FadeOut + slideOutHorizontally(Kit.default()) { -d * it / 6 })
            },
            label = "card",
        ) { m ->
            Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (m) {
                    Metric.FPS -> FpsCard()
                    Metric.CPU -> CpuCard()
                    Metric.GPU -> GpuCard()
                    Metric.RAM -> RamCard()
                }
            }
        }
    }
}

/** A card's big live number and its unit, then [trailing] facts at the end. */
@Composable
private fun CardHead(value: String, unit: String, trailing: @Composable RowScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value, style = num(28.sp, 620), color = cs.primary)
        Text(unit, style = label(11.sp), color = cs.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Temp(c: Float) {
    val cs = MaterialTheme.colorScheme
    Text("%.1f°".format(c), style = num(13.sp, 600), color = if (c >= HOT_SOC) cs.error else cs.onSurface)
}

@Composable
private fun ColumnScope.FpsCard() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val s by o.bus.snapshot.collectAsState()
    val fps by o.fps.realFpsHistory.collectAsState()
    val panel = rememberPanelRates()
    CardHead("${fps.lastOrNull()?.toInt() ?: 0}", "fps") {
        if (s.thermalHeadroomReduced) Text(
            "Throttled", Modifier.clip(CircleShape).background(cs.errorContainer).padding(horizontal = 8.dp, vertical = 2.dp),
            style = label(10.5.sp, 600), color = cs.onErrorContainer,
        )
        Text("${panel.hz} Hz", style = num(13.sp, 600), color = cs.onSurfaceVariant)
    }
    val shown = fps.filter { it > 0 }
    if (shown.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("Min" to shown.min(), "Avg" to shown.average().toFloat(), "Max" to shown.max()).forEach { (k, v) ->
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$k ", style = label(10.5.sp), color = cs.onSurfaceVariant)
                Text("${v.toInt()}", style = num(12.sp, 600), color = cs.onSurface)
            }
        }
    }
    // Scale to the first refresh step the peak fits under.
    val peak = fps.maxOrNull() ?: 0f
    val top = panel.ladder.firstOrNull { it >= peak - 2f } ?: maxOf(panel.hz, peak.roundToInt())
    Graph(fps, top + 5f, panel.ladder.map(Int::toFloat), Modifier.weight(1f))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("On screen", style = label(11.sp, 600), color = cs.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Widget.entries.forEach { w ->
            val on = o.widgets.settings.enabled(w)
            Text(
                if (w == Widget.PROCESSES) "Proc" else if (w == Widget.TEMP) "Temp" else w.label,
                Modifier.clip(RoundedCornerShape(if (on) 14.dp else 8.dp))
                    .background(if (on) cs.secondaryContainer else cs.surfaceContainerHigh)
                    .clickable { o.widgets.settings.setEnabled(w, !on) }
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                style = label(11.sp, if (on) 600 else 500),
                color = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant,
            )
        }
        RoundButton(R.drawable.sym_edit, "Widget settings", { o.state.open(Page.WIDGETS) }, size = 28.dp)
    }
}

@Composable
private fun ColumnScope.CpuCard() {
    val o = LocalOverlay.current
    val s by o.bus.snapshot.collectAsState()
    val procs by o.widgets.processes.collectAsState()
    val icons = rememberAppIconCache()
    CardHead("${s.cpuUsageTotal}", "%") { Temp(s.cpuTempC) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { s.cpuClusters.forEach { ClusterRow(it, s.cpuCoreUsage) } }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        procs.forEach { ProcessRow(it, icons, 18.dp, 12.sp) }
    }
}

/** Live and max clock, the thermal cap while one is in force, and each core's load. */
@Composable
private fun ClusterRow(c: ClusterSnap, load: List<Float>) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(c.name, Modifier.width(44.dp), style = label(11.sp, 600), color = cs.onSurfaceVariant, maxLines = 1)
        Text(ghz(c.curKhz), style = num(12.sp, 600), color = cs.onSurface)
        Text("/ ${ghz(c.hwMaxKhz)}", style = label(10.5.sp), color = cs.onSurfaceVariant)
        if (c.scalingMaxKhz < c.hwMaxKhz) Tag("Cap ${ghz(c.scalingMaxKhz)}", warn = true)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            c.cpuIds.forEach { id ->
                Box(Modifier.width(8.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(cs.surfaceContainerHighest), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight((load.getOrNull(id) ?: 0f) / 100f).background(cs.primary))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.GpuCard() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val s by o.bus.snapshot.collectAsState()
    val steps = o.perf.gpuInfo.availableHz
    CardHead("${s.gpuUsage}", "%") {
        Text("${s.gpuFreqMhz} MHz", style = num(13.sp, 600), color = cs.onSurfaceVariant)
        Temp(s.gpuTempC)
    }
    Graph(s.gpuUsageHistory.map(Int::toFloat), 100f, listOf(50f), Modifier.weight(1f))
    if (steps.isEmpty()) return
    GpuLadder(s, Modifier.fillMaxWidth().height(40.dp), 3.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${steps.first() / 1_000_000}", style = num(11.sp, 500), color = cs.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (s.gpuCapHz in 1 until steps.last()) Tag("Cap ${s.gpuCapHz / 1_000_000} MHz", warn = true)
        Spacer(Modifier.weight(1f))
        Text("${steps.last() / 1_000_000} MHz", style = num(11.sp, 500), color = cs.onSurfaceVariant)
    }
}

@Composable
private fun ColumnScope.RamCard() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val s by o.bus.snapshot.collectAsState()
    val icons = rememberAppIconCache()
    var system by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(emptyMap<Boolean, List<AppMemory>>()) }
    LaunchedEffect(Unit) {
        val source = RunningApps(ctx)
        while (true) {
            // Icons render here, off the UI thread; the cache serves every later sample.
            apps = withContext(Dispatchers.IO) {
                source.sample().groupBy { it.system }.mapValues { (_, v) -> v.take(TOP_APPS).onEach { icons.icon(it.pkg, ICON_PX) } }
            }
            delay(3_000)
        }
    }
    CardHead("%.1f".format(s.ramUsedGb), "/ ${ramGb(s)} GB") {
        if (s.swapTotalGb > 0) {
            val full = s.swapUsedGb / s.swapTotalGb > 0.95f
            Text(
                if (full) "swap full" else "swap %.1f / %.0f".format(s.swapUsedGb, ceil(s.swapTotalGb)),
                style = label(11.sp), color = if (full) cs.error else cs.onSurfaceVariant,
            )
        }
    }
    Segmented(listOf("User" to false, "System" to true), system, { system = it }, height = 28.dp)
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(apps[system].orEmpty(), key = { it.pkg }) { AppRow(it.pkg, mem(it.pssKb), icons, size = 20.dp, text = 12.sp) }
    }
}

/** [values] as a line under [top], with labelled [guides] and a dashed average. */
@Composable
private fun Graph(values: List<Float>, top: Float, guides: List<Float>, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val style = num(9.5.sp, 500).copy(color = cs.onSurfaceVariant)
    Canvas(modifier.fillMaxWidth()) {
        fun y(v: Float) = size.height - v / top * size.height
        val marks = guides.filter { it < top }.map { it to measurer.measure("${it.roundToInt()}", style) }
        val w = size.width - (marks.maxOfOrNull { it.second.size.width + 4.dp.toPx() } ?: 0f)
        marks.forEach { (g, t) ->
            drawLine(cs.outlineVariant, Offset(0f, y(g)), Offset(w, y(g)), 1.dp.toPx())
            drawText(t, topLeft = Offset(size.width - t.size.width, y(g) - t.size.height / 2f))
        }
        if (values.size < 2) return@Canvas
        val dx = w / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * dx, y(v)) }
        val avg = values.average().toFloat()
        drawLine(cs.tertiary, Offset(0f, y(avg)), Offset(w, y(avg)), 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
        drawPath(path, cs.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ── widgets ───────────────────────────────────────────────────────────────

/** Settings for the floating widgets; open widgets can be dragged meanwhile. */
@Composable
fun WidgetsPage() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val set = o.widgets.settings
    DisposableEffect(Unit) {
        o.widgets.editing.value = true
        onDispose { o.widgets.editing.value = false }
    }
    SubPage("On-screen widgets", trailing = {
        Text("${set.count} on", style = label(11.sp), color = cs.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
    }) {
        Widget.entries.forEachIndexed { i, w ->
            Block(shape = Kit.connected(i, Widget.entries.size), padding = 10.dp) {
                SettingRow(w.label) { Toggle(set.enabled(w), { set.setEnabled(w, it) }) }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    w.fields.forEach { f ->
                        val on = set.shows(w, f)
                        Text(
                            f.label,
                            Modifier.clip(RoundedCornerShape(if (on) 10.dp else 6.dp))
                                .background(if (on) cs.secondaryContainer else cs.surfaceContainerHigh)
                                .clickable { set.setShows(w, f, !on) }
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                            style = label(10.5.sp, if (on) 600 else 500),
                            color = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Block(padding = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Opacity", style = label(12.sp), color = cs.onSurface)
                Slider(set.opacity, { set.opacity = it }, Modifier.weight(1f), range = 0.3f..1f)
                Text("${(set.opacity * 100).toInt()}%", style = num(12.sp, 600), color = cs.onSurface)
            }
        }
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Glyph(R.drawable.sym_drag_pan, cs.onSurfaceVariant, 14.dp)
            Text("Drag a widget to move it", style = label(11.sp), color = cs.onSurfaceVariant)
        }
    }
}
