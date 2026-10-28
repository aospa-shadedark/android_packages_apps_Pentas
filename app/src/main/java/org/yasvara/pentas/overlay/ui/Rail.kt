/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import org.yasvara.pentas.R
import org.yasvara.pentas.data.model.AppRef
import org.yasvara.pentas.overlay.LocalOverlay
import org.yasvara.pentas.overlay.Page
import org.yasvara.pentas.overlay.dock.openInWindow
import org.yasvara.pentas.ui.kit.RoundButton
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num

/** mm:ss, or h:mm:ss past an hour. */
internal fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

/** The 52 dp rail beside the panel: session time, ping, record, window, app shortcuts. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Rail(close: () -> Unit) {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(1000) } }
    val recording by o.state.recording
    val since by o.state.recordingSinceMs
    Column(
        Modifier.width(52.dp).fillMaxHeight().clip(RoundedCornerShape(26.dp))
            .background(cs.surface.copy(alpha = 0.96f)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(clock(now - o.session.startElapsedMs), style = num(12.sp, 560), color = cs.onSurface)
        Ping()
        Box(Modifier.width(22.dp).height(1.dp).background(cs.outlineVariant))
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (recording) cs.errorContainer else cs.surfaceContainerHigh)
                .combinedClickable(
                    role = Role.Button,
                    onLongClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        o.state.open(Page.RECORD)
                    },
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        o.record.toggle()
                    },
                )
                .semantics {
                    contentDescription = if (recording) "Stop screen recording" else "Start screen recording"
                    stateDescription = if (recording) "Recording" else "Not recording"
                },
            contentAlignment = Alignment.Center,
        ) {
            if (recording && since > 0) Text(clock(now - since).takeLast(5), style = num(9.5.sp, 600), color = cs.onErrorContainer)
            else Box(Modifier.size(12.dp).clip(CircleShape).background(cs.error))
        }
        RoundButton(R.drawable.sym_picture_in_picture, "Open an app in a window", {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            o.state.appsPin.value = false; o.state.open(Page.APPS)
        }, size = 40.dp)
        ShortcutColumn(Modifier.weight(1f), close)
        RoundButton(R.drawable.sym_add, "Add shortcut", {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            o.state.appsPin.value = true; o.state.open(Page.APPS)
        }, size = 40.dp, active = false)
    }
}

@Composable
private fun Ping() {
    val o = LocalOverlay.current
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val ms = o.network.latencyMs.value
    val source = o.network.pingSource.value
    val bars = when { ms <= 0 -> 0; ms < 60 -> 3; ms < 120 -> 2; else -> 1 }
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
            o.state.open(Page.PING)
        }.semantics {
            val kind = when (source) {
                org.yasvara.pentas.telemetry.network.PingSource.GAME_ENDPOINTS -> "Game endpoint latency"
                org.yasvara.pentas.telemetry.network.PingSource.NETWORK_ROUTE -> "Network route latency"
                org.yasvara.pentas.telemetry.network.PingSource.NONE -> "Network latency"
            }
            contentDescription = if (ms > 0) "$kind $ms milliseconds" else "$kind unavailable"
        }.padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            listOf(4, 7, 11).forEachIndexed { i, h ->
                Box(Modifier.size(4.dp, h.dp).clip(RoundedCornerShape(1.5.dp))
                    .background(if (i < bars) (if (bars == 1) cs.error else cs.primary) else cs.outlineVariant))
            }
        }
        Text(if (ms > 0) "$ms ms" else "—", style = label(10.sp), color = cs.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutColumn(modifier: Modifier, close: () -> Unit) {
    val o = LocalOverlay.current
    val ctx = LocalContext.current
    val view = LocalView.current
    val mode = o.session.mode
    val page by o.state.page
    var apps by remember { mutableStateOf(emptyList<AppRef>()) }
    var rev by remember { mutableLongStateOf(0) }
    LaunchedEffect(rev, page, mode, o.pkg) { apps = o.shortcuts.load(mode, o.pkg, SHORTCUTS) }
    val pinned = o.shortcuts.pinned(mode)
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        apps.forEach { app ->
            val bmp = remember(app.packageName) { app.icon?.toBitmap(96, 96)?.asImageBitmap() }
            Box(
                Modifier.size(40.dp).clip(CircleShape).combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = "Unpin ${app.label}",
                    onLongClick = if (app.packageName in pinned) ({
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        o.shortcuts.unpin(mode, app.packageName); rev++
                    }) else null,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        openInWindow(ctx, app.packageName); close()
                    },
                ).semantics { contentDescription = "Open ${app.label} in a window" },
            ) { bmp?.let { Image(it, null, Modifier.size(40.dp)) } }
        }
    }
}

private const val SHORTCUTS = 20
