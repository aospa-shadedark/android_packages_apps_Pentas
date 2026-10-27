/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.ui

import android.content.Intent
import android.os.UserHandle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.delay
import org.yasvara.pentas.R
import org.yasvara.pentas.app.GameActivity
import org.yasvara.pentas.overlay.LocalOverlay
import org.yasvara.pentas.overlay.Page
import org.yasvara.pentas.overlay.TunerTab
import org.yasvara.pentas.overlay.tiles.TileAction
import org.yasvara.pentas.overlay.tiles.tilePainter
import org.yasvara.pentas.session.SidebarMode
import org.yasvara.pentas.thermal.KernelNodeIO
import org.yasvara.pentas.thermal.ThermalNodes
import org.yasvara.pentas.thermal.ThermalProfiles
import org.yasvara.pentas.ui.kit.Glyph
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.kit.RoundButton
import org.yasvara.pentas.ui.kit.Slider
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num
import org.yasvara.pentas.ui.kit.segmentShape

@Composable
fun Home(mode: Int) {
    val o = LocalOverlay.current
    val listOpen by o.state.profileList
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(Kit.Gap)) {
            Header()
            if (mode == SidebarMode.MODE_GAME) Monitor(Modifier.weight(1f)) { Controls() }
            else Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Controls() }
        }
        if (listOpen) ProfileList()
    }
}

/** Everything under the live tiles. */
@Composable
private fun ColumnScope.Controls() {
    ThermalSwitcher()
    MediaCard()
    BrightnessRow()
    Toggles()
    Tools()
}

@Composable
fun rememberClock(): String {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(60_000 - now % 60_000) }
    }
    return DateFormat.getTimeFormat(ctx).format(now)
}

/** Battery chip: tap toggles bypass charging where the kernel has it, long-press opens Battery. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Header() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val s by o.bus.snapshot.collectAsState()
    val cr = ctx.contentResolver
    val canBypass = remember { KernelNodeIO.exists(ThermalNodes.BYPASS_CHARGE) }
    var bypass by remember {
        mutableStateOf(Settings.System.getIntForUser(cr, ThermalProfiles.KEY_BYPASS_CHARGING, 0, UserHandle.USER_CURRENT) == 1)
    }
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(rememberClock(), style = num(13.sp, 560), color = cs.onSurface, modifier = Modifier.padding(start = 4.dp))
        Row(
            Modifier.height(24.dp).clip(CircleShape).background(if (bypass) cs.primaryContainer else cs.surfaceContainerHigh)
                .combinedClickable(
                    onLongClick = { o.state.open(Page.BATTERY) },
                    onClick = {
                        if (!canBypass) o.state.open(Page.BATTERY) else {
                            bypass = !bypass
                            Settings.System.putIntForUser(cr, ThermalProfiles.KEY_BYPASS_CHARGING, if (bypass) 1 else 0, UserHandle.USER_CURRENT)
                        }
                    },
                ).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            BatteryGlyph(s.batteryCapacity, s.batteryCharging)
            Text("${s.batteryCapacity}%", style = num(11.5.sp, 600), color = if (bypass) cs.onPrimaryContainer else cs.onSurface)
            val w = if (bypass) "Bypass" else (if (s.batteryCharging) "+" else "−") + "%.1f W".format(s.batteryPowerW)
            Text(
                w + " · %.0f°".format(s.batteryTempC),
                style = label(10.5.sp),
                color = when { s.batteryTempC >= 42f -> cs.error; bypass -> cs.onPrimaryContainer; else -> cs.onSurfaceVariant },
            )
        }
        Spacer(Modifier.weight(1f))
        RoundButton(R.drawable.sym_tune, "Tuner", { o.state.tuner(o.state.tunerTab.value) })
        RoundButton(R.drawable.sym_edit, "Edit", { o.state.open(Page.EDIT) })
        RoundButton(R.drawable.sym_settings, "Settings", {
            ctx.startActivity(Intent(ctx, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_PACKAGE, o.pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
    }
}

/** Battery outline whose fill is the charge level. */
@Composable
private fun BatteryGlyph(level: Int, charging: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp, 10.dp).clip(RoundedCornerShape(3.dp)).background(cs.outline).padding(1.5.dp)) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp)).background(cs.surfaceContainerHigh)) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0, 100) / 100f)
                        .background(if (charging) cs.primary else if (level <= 15) cs.error else cs.onSurface)
                )
            }
        }
        Box(Modifier.size(1.5.dp, 4.dp).background(cs.outline))
    }
}

// ── media ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaCard() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val np by o.media.now
    AnimatedVisibility(np != null) {
        val n = np ?: return@AnimatedVisibility
        var t by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
        LaunchedEffect(n.playing) { while (n.playing) { t = android.os.SystemClock.elapsedRealtime(); delay(1000) } }
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainer)
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val cookie = MaterialShapes.Cookie9Sided.toShape()
            Box(Modifier.size(40.dp).clip(cookie).background(cs.tertiaryContainer), contentAlignment = Alignment.Center) {
                n.art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Glyph(R.drawable.sym_music_note, cs.onTertiaryContainer, 18.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(n.title, style = label(12.5.sp, 600), color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(n.artist, n.app).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = label(10.5.sp), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (n.durationMs > 0) {
                    val p = (n.position(t).toFloat() / n.durationMs).coerceIn(0f, 1f)
                    LinearWavyProgressIndicator(
                        progress = { p }, modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = cs.primary, trackColor = cs.outlineVariant,
                        amplitude = { if (n.playing) 1f else 0f },
                    )
                }
            }
            RoundButton(R.drawable.sym_skip_previous, "Previous", o.media::previous, size = 28.dp)
            MediaPlay(n.playing, o.media::playPause)
            RoundButton(R.drawable.sym_skip_next, "Next", o.media::next, size = 28.dp)
        }
    }
}

@Composable
private fun MediaPlay(playing: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val r by animateDpAsState(if (playing) 12.dp else 20.dp, Kit.fast(), label = "play")
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(r)).background(cs.primary).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Glyph(if (playing) R.drawable.sym_pause else R.drawable.sym_play_arrow, cs.onPrimary, 20.dp) }
}

// ── brightness, toggles, tools ────────────────────────────────────────────

@Composable
private fun BrightnessRow() {
    val o = LocalOverlay.current
    val level by o.brightness.level
    Slider(level, o.brightness::set, Modifier.padding(horizontal = 2.dp), height = 32.dp, icon = R.drawable.sym_brightness_7)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Toggles() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val tiles = o.tiles.toggles
    Row(Modifier.fillMaxWidth().height(38.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        tiles.forEachIndexed { i, t ->
            val on by t.observeEnabled()
            val shape = segmentShape(i, tiles.size, on, 19.dp)
            val bg by animateColorAsState(if (on) cs.primary else cs.surfaceContainerHigh, label = "tgbg")
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(shape).background(bg)
                    .combinedClickable(onLongClick = t::onLongClick, onClick = {
                        view.performHapticFeedback(
                            if (on) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.TOGGLE_ON,
                        )
                        t.toggle()
                    }),
                contentAlignment = Alignment.Center,
            ) { TileIcon(t, if (on) cs.onPrimary else cs.onSurface) }
        }
    }
}

@Composable
private fun TileIcon(t: TileAction, tint: androidx.compose.ui.graphics.Color) {
    androidx.compose.material3.Icon(tilePainter(t), t.label, Modifier.size(19.dp), tint = tint)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tools() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val tools = o.tiles.tools
    val pages = tools.chunked(4)
    if (pages.isEmpty()) return
    val pager = rememberPagerState { pages.size }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalPager(pager, Modifier.fillMaxWidth().height(40.dp), pageSpacing = 8.dp) { p ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pages[p].forEach { t ->
                    val on by t.observeEnabled()
                    val widgets = t.id == "widgets"
                    val count = o.widgets.settings.count
                    val active = on || (widgets && count > 0)
                    Column(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (active) cs.secondaryContainer else cs.surfaceContainer)
                            .combinedClickable(onLongClick = t::onLongClick, onClick = t::toggle),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        TileIcon(t, if (active) cs.onSecondaryContainer else cs.onSurface)
                        Text(
                            if (widgets && count > 0) "${t.label} · $count" else t.label,
                            style = label(10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (active) cs.onSecondaryContainer else cs.onSurfaceVariant,
                        )
                    }
                }
                repeat(4 - pages[p].size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (pages.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
            repeat(pages.size) { i ->
                val w by animateDpAsState(if (i == pager.currentPage) 12.dp else 4.dp, Kit.fast(), label = "dot")
                Box(Modifier.size(w, 4.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) cs.primary else cs.outlineVariant))
            }
        }
    }
}

/** Horizontal fling distance (px) that counts as a swipe on the profile switcher. */
internal const val SWIPE_PX = 48f

internal fun swiped(dx: Float) = abs(dx) >= SWIPE_PX
