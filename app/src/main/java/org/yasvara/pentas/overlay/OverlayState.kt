/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import javax.inject.Inject
import javax.inject.Singleton

/** Where the open panel is. One page at a time; the rail stays. */
enum class Page { HOME, WIDGETS, TUNER, BATTERY, BLUETOOTH, WIFI, PING, RECORD, EDIT, APPS }

enum class TunerTab { PERF, GRAPHICS, TOUCH, SOUND }

/** The game home's live tiles, in tile order. */
enum class Metric { FPS, CPU, GPU, RAM }

/** Session-scoped sidebar state. Lives as long as the :ui process, reset per session. */
@Singleton
class OverlayState @Inject constructor() {
    val page = mutableStateOf(Page.HOME)
    /** The tile opened into its detail card; null shows all four. */
    val metric = mutableStateOf<Metric?>(null)
    val tunerTab = mutableStateOf(TunerTab.GRAPHICS)
    val lastTuneTab = mutableStateOf(TunerTab.GRAPHICS)
    val profileList = mutableStateOf(false)
    /** The game's thermal profile index, loaded when the panel opens. */
    val profile = mutableIntStateOf(0)

    val recording = mutableStateOf(false)
    val recordingSinceMs = mutableLongStateOf(0L)

    val statsRecording = mutableStateOf(false)
    val statsSeconds = mutableIntStateOf(0)

    val gestureLock = mutableStateOf(false)
    val keyboard = mutableStateOf(false)
    /** APPS page purpose: true pins a rail shortcut, false opens the app in a window. */
    val appsPin = mutableStateOf(false)

    /** Set by SessionService for the session's lifetime. */
    var onGestureLock: (Boolean) -> Unit = {}
    var onStatsRecord: (Boolean) -> Unit = {}

    fun open(p: Page) { profileList.value = false; page.value = p }
    fun tuner(tab: TunerTab) {
        if (tab != TunerTab.SOUND) lastTuneTab.value = tab
        tunerTab.value = tab
        open(Page.TUNER)
    }
    fun home() = open(Page.HOME)
    /** Opens [m]'s card, or folds it back when it is the open one. */
    fun toggle(m: Metric) { metric.value = m.takeIf { it != metric.value } }

    fun reset() {
        page.value = Page.HOME
        metric.value = null
        tunerTab.value = TunerTab.GRAPHICS
        lastTuneTab.value = TunerTab.GRAPHICS
        profileList.value = false
        keyboard.value = false
    }
}
