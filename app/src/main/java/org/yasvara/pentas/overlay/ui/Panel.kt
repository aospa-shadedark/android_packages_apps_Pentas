/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import org.yasvara.pentas.R
import org.yasvara.pentas.overlay.LocalOverlay
import org.yasvara.pentas.overlay.Page
import org.yasvara.pentas.overlay.TunerTab
import org.yasvara.pentas.session.SidebarMode
import org.yasvara.pentas.overlay.ui.tuner.Tuner
import org.yasvara.pentas.ui.kit.Glyph
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.kit.PageHeader
import org.yasvara.pentas.ui.kit.RoundButton
import org.yasvara.pentas.ui.kit.label

val PanelWidth = 320.dp

/** The 320 dp panel: one page at a time, swapped in place. */
@Composable
fun Panel(mode: Int, onClose: () -> Unit) {
    val o = LocalOverlay.current
    val page by o.state.page
    Column(
        Modifier.width(PanelWidth).fillMaxHeight().clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
    ) {
        if (mode == SidebarMode.MODE_GAME) GameTabs(onClose)
        AnimatedContent(
            page,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val forward = targetState != Page.HOME
                (fadeIn(spring(stiffness = 1600f)) + slideInHorizontally(spring(0.8f, 380f)) { if (forward) it / 6 else -it / 6 })
                    .togetherWith(fadeOut(spring(stiffness = 3800f)))
            },
            label = "page",
        ) { p ->
            when (p) {
                Page.HOME -> Home(mode)
                Page.WIDGETS -> WidgetsPage()
                Page.TUNER -> Tuner(mode)
                Page.BATTERY -> BatteryPage()
                Page.BLUETOOTH -> BluetoothPage()
                Page.WIFI -> WifiPage()
                Page.PING -> PingPage()
                Page.RECORD -> RecordPage()
                Page.EDIT -> EditPage()
                Page.APPS -> AppsPage()
            }
        }
    }
}

/** The common game actions stay one tap away from both the quick panel and tuner. */
@Composable
private fun GameTabs(onClose: () -> Unit) {
    val o = LocalOverlay.current
    val page by o.state.page
    val tunerTab by o.state.tunerTab
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val ctx = LocalContext.current
    val gameIcon = remember(o.pkg) {
        o.pkg?.let { pkg -> runCatching {
            ctx.packageManager.getApplicationIcon(pkg).toBitmap(72, 72).asImageBitmap()
        }.getOrNull() }
    }
    val game = remember(o.pkg) {
        o.pkg?.let { pkg -> runCatching {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg) }.orEmpty()
    }
    val selected = when {
        page == Page.HOME -> 0
        page == Page.TUNER && tunerTab == TunerTab.SOUND -> 2
        page == Page.TUNER -> 1
        else -> -1
    }
    val tabs = listOf(
        Triple("Quick", R.drawable.sym_bolt, { o.state.home() }),
        Triple("Tune", R.drawable.sym_tune, { o.state.tuner(o.state.lastTuneTab.value) }),
        Triple("Sound", R.drawable.sym_graphic_eq, { o.state.tuner(TunerTab.SOUND) }),
    )
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("PENTAS", style = label(10.sp, 700), color = cs.primary)
        gameIcon?.let {
            Image(it, game, Modifier.width(22.dp).height(22.dp).clip(RoundedCornerShape(7.dp)))
        }
        Text(game, style = label(10.sp), color = cs.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        RoundButton(R.drawable.sym_close, "Close game panel", {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onClose()
        }, size = 26.dp)
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { index, (title, icon, action) ->
            val active = index == selected
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "panel tab press")
            val background by animateColorAsState(
                if (active) cs.secondaryContainer else cs.surfaceContainerHigh,
                label = "panel tab color",
            )
            val foreground = if (active) cs.onSecondaryContainer else cs.onSurfaceVariant
            Row(
                Modifier.weight(1f).height(40.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(RoundedCornerShape(15.dp)).background(background)
                    .selectable(selected = active, role = Role.Tab,
                        interactionSource = interaction, indication = null) {
                        if (!active) {
                            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                            action()
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            ) {
                Glyph(icon, foreground, 16.dp)
                Text(title, style = label(11.sp, if (active) 650 else 500),
                    color = foreground)
            }
        }
    }
}

/** A detail page: header with back to home, then scrolling content. */
@Composable
fun SubPage(title: String, trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val o = LocalOverlay.current
    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(Kit.Gap)) {
        PageHeader(title, onBack = { o.state.home() }, back = R.drawable.sym_arrow_back, trailing = trailing)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Kit.Gap),
            content = content,
        )
    }
}
