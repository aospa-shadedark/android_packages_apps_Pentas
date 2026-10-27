/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.ui.tuner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.yasvara.pentas.R
import org.yasvara.pentas.overlay.LocalOverlay
import org.yasvara.pentas.overlay.TunerTab
import org.yasvara.pentas.session.SidebarMode
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.kit.PageHeader
import org.yasvara.pentas.ui.kit.Segmented
import org.yasvara.pentas.ui.tuning.GameClocks
import org.yasvara.pentas.ui.tuning.GraphicsEditor
import org.yasvara.pentas.ui.tuning.SoundEditor
import org.yasvara.pentas.ui.tuning.TouchEditor

/** The tuner inside the sidebar: tabs on top, the shared per-game editors below. */
@Composable
fun Tuner(mode: Int) {
    val o = LocalOverlay.current
    val pkg = o.pkg ?: return
    val game = mode == SidebarMode.MODE_GAME
    val tabs = if (game) listOf(TunerTab.PERF, TunerTab.GRAPHICS, TunerTab.TOUCH)
        else listOf(TunerTab.GRAPHICS, TunerTab.SOUND)
    var tab by o.state.tunerTab
    val soundPage = game && tab == TunerTab.SOUND
    if (!soundPage && tab !in tabs) tab = tabs.first()
    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(Kit.Gap)) {
        PageHeader(if (soundPage) "Sound" else "Tuner", onBack = { o.state.home() }, back = R.drawable.sym_arrow_back)
        if (!soundPage) Segmented(
            tabs.map { (if (it == TunerTab.GRAPHICS && !game) "Display" else it.label) to it },
            selected = tab, onSelect = { o.state.tuner(it) },
        )
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Kit.Gap),
        ) {
            when (tab) {
                TunerTab.PERF -> GameClocks(pkg)
                TunerTab.GRAPHICS -> GraphicsEditor(pkg, game)
                TunerTab.TOUCH -> TouchEditor(pkg, pad = true)
                TunerTab.SOUND -> SoundEditor(pkg)
            }
        }
    }
}

private val TunerTab.label get() = when (this) {
    TunerTab.PERF -> "Perf"
    TunerTab.GRAPHICS -> "Graphics"
    TunerTab.TOUCH -> "Touch"
    TunerTab.SOUND -> "Sound"
}
