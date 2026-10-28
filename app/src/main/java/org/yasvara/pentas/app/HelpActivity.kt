/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yasvara.pentas.R
import org.yasvara.pentas.charging.ChargingProfileListActivity
import org.yasvara.pentas.device.DeviceSupport
import org.yasvara.pentas.settings.diagnostics.DiagnosticsActivity
import org.yasvara.pentas.settings.fpsstats.FpsStatsActivity
import org.yasvara.pentas.thermal.custom.CustomProfileListActivity
import org.yasvara.pentas.ui.kit.Glyph
import org.yasvara.pentas.ui.kit.Sym
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.theme.PentasTheme

class HelpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PentasTheme { HelpScreen(onBack = { finish() }) } }
    }
}

private data class HelpTopic(
    val title: String,
    val summary: String,
    val steps: List<String>,
    val keywords: String = "",
)

private val helpTopics = listOf(
    HelpTopic(
        "Get around Pentas",
        "The bottom bar links your game library, sessions and app settings.",
        listOf(
            "Tap Games, Sessions or Settings in the bottom bar, or swipe horizontally between those pages.",
            "The selected bar item follows the page motion. You can also drag the bar itself to move between tabs.",
            "Press Back from Sessions or Settings to return to Games.",
        ), "navigation tabs bottom bar swipe games sessions settings",
    ),
    HelpTopic(
        "Add games and set profiles",
        "Keep each game's controls together, with global settings as a fallback.",
        listOf(
            "Open Games and tap + to add an installed app.",
            "Open a game card to find its Graphics, Touch, Performance and Sound controls.",
            "Choose Follow global/default when you want that game to inherit the shared setting.",
        ), "library add app graphics touch performance sound profile global",
    ),
    HelpTopic(
        "Graphics and app controls",
        "Graphics choices are saved per app and use Android or ROM controls where available.",
        listOf(
            "Open a game and choose Graphics. Render resolution below 1× uses Android Game Mode and takes effect the next time the game starts.",
            "Display color and GPU composition use platform controls. ANGLE appears only when its system driver is detected; driver changes take effect on the next launch.",
            "If a game looks wrong after changing a graphics option, restore its default and relaunch it.",
        ), "graphics resolution scale color ANGLE composition app game restart",
    ),
    HelpTopic(
        "Use the in-game sidebar",
        "Open quick controls and game tools without leaving the game.",
        listOf(
            "While a game is active, tap the small edge handle to open the panel; drag it to move it or dock it on the other side.",
            "Use the rail to switch between the home panel, live monitor and game tools. Scroll within a page for more controls; use Edit to arrange quick tiles.",
            "Tap the handle again or outside the panel to close it.",
        ), "bar overlay handle drag quick tiles edit rail",
    ),
    HelpTopic(
        "Live monitor and recorded stats",
        "Live readings are separate from saved performance sessions.",
        listOf(
            "Open the live monitor and tap FPS, CPU, GPU or RAM to see its current reading and graph. The FPS card also shows recent min, average and peak.",
            "To save a history, open Record stats from the game tools and turn it on. Tap it again to stop and save the session.",
            "Review saved charts and summaries from the Sessions tab. Samples are collected about once per second; thread details are best-effort.",
        ), "fps cpu gpu ram record session charts history",
    ),
    HelpTopic(
        "Network latency and traffic",
        "The sidebar network page shows best measured RTT and this game's data rate.",
        listOf(
            "Open the network indicator in the in-game rail to see latency, download and upload rates for the game's UID.",
            "When Pentas can identify remote game endpoints, RTT is measured with ICMP or a TCP-connect fallback. If it cannot, the page labels the DNS server or gateway route fallback.",
            "This is an endpoint estimate, not the game's own server-side ping; VPNs, firewalls, UDP-only servers and network routing can make it differ.",
        ), "network ping latency rtt traffic download upload dns gateway vpn",
    ),
    HelpTopic(
        "Performance and clock limits",
        "Per-game tuning can override the global clock profile where supported.",
        listOf(
            "Open a game's Performance controls for its limits, or use Global tuning from Games for the shared profile.",
            "Default limits are recommended. Pentas asks you to confirm before applying clock changes; higher limits can increase heat and battery use and may cause instability or throttling.",
            "Pentas discovers CPU/GPU frequency tables from the running device. A detected control marked read-only means the ROM or kernel does not grant Pentas write access; the app cannot grant itself that access. Open Diagnostics from Help to copy the paths Pentas found when reporting a missing control.",
        ), "clock cpu gpu performance heat warning default tuning",
    ),
    HelpTopic(
        "Sound and Dolby profiles",
        "Pentas can select supported Dolby profiles; Dolby's own app remains the place for its detailed sound controls.",
        listOf(
            "Open a game's Sound controls and choose a profile, or leave it on Follow global/default.",
            "The Xiaomi Dolby integration appears only when its control provider is installed and available. Dolby's advanced equalizer and sound options stay in the Dolby app.",
            "Automatic per-app switching requires App Profile monitoring in Dolby and Usage Access. If those are off, enable them in Dolby before expecting automatic switching.",
        ), "dolby audio sound xiaomi dynamic music movie game usage access monitoring",
    ),
    HelpTopic(
        "Thermal and charging options",
        "These controls depend on the ROM's thermal service and the device's charging hardware.",
        listOf(
            "Use the Thermal card in Settings to open the available system or custom profiles.",
            "Charging profiles are shown only when the required thermal integration is present. Bypass charging is offered only on devices that report support.",
            "Keep the default profile if you are unsure; aggressive tuning can increase temperature or affect charging behavior.",
        ), "thermal charging bypass battery profile custom",
    ),
    HelpTopic(
        "Quick tiles, widgets and session settings",
        "Choose what appears in the bar and how Pentas behaves while a game is open.",
        listOf(
            "In the sidebar, open Edit to add, remove or rearrange supported quick tiles. Open Widgets to choose which readings appear over the game.",
            "The Settings tab contains notification, call, brightness and session behavior options.",
            "Some tiles and settings are conditional on the ROM, device hardware or required system permission; unavailable controls are hidden or may not report data.",
        ), "widgets tiles edit dnd calls brightness notifications permissions settings",
    ),
)

@Composable
private fun HelpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val cs = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(helpTopics.first().title) }
    val visible = remember(query) {
        if (query.isBlank()) helpTopics else helpTopics.filter { topic ->
            (topic.title + " " + topic.summary + " " + topic.steps.joinToString(" ") + " " + topic.keywords)
                .contains(query.trim(), ignoreCase = true)
        }
    }

    Screen(title = "Help & guide", onBack = onBack) {
        Text("Find a feature, follow the steps, and jump straight to the main screens.",
            style = label(14.sp), color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HelpShortcut("Games", R.drawable.sym_videogame_asset) {
                context.startActivity(Intent(context, LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
            HelpShortcut("Sessions", R.drawable.sym_monitoring) {
                context.startActivity(Intent(context, FpsStatsActivity::class.java))
            }
            HelpShortcut("Tuning", R.drawable.sym_tune) {
                context.startActivity(Intent(context, GlobalActivity::class.java))
            }
            HelpShortcut("Settings", R.drawable.sym_settings) {
                context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
            HelpShortcut("Diagnostics", R.drawable.sym_developer_board) {
                context.startActivity(Intent(context, DiagnosticsActivity::class.java))
            }
        }

        Row(
            Modifier.fillMaxWidth().clip(CircleShape).background(cs.surfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Glyph(R.drawable.sym_search, cs.onSurfaceVariant, 20.dp)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search help", style = label(14.sp), color = cs.onSurfaceVariant)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = label(14.sp).copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                )
            }
            if (query.isNotEmpty()) {
                Text("Clear", Modifier.clickable(role = Role.Button) { query = "" },
                    style = label(12.sp, 600), color = cs.primary)
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(cs.secondaryContainer)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text("Feature availability", style = label(14.sp, 650), color = cs.onSecondaryContainer)
            Text(
                "Pentas checks the device at runtime, so detection does not need a device-tree entry for each model. Some controls still require ROM or kernel access; if a clock control is read-only, only the system image can grant write permission. Keep default performance and thermal settings if you are unsure.",
                style = label(12.5.sp), color = cs.onSecondaryContainer,
            )
        }

        Text("Browse topics", style = label(19.sp, 600), color = cs.onSurface,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        if (visible.isEmpty()) {
            Text("No guide topics match that search.", style = label(13.sp), color = cs.onSurfaceVariant,
                modifier = Modifier.padding(8.dp))
        }
        visible.forEach { topic ->
            val isOpen = expanded == topic.title
            val arrowRotation by animateFloatAsState(
                if (isOpen) 180f else 0f,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 480f),
                label = "help topic arrow",
            )
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(cs.surfaceContainer)
                    .clickable(role = Role.Button) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_TICK)
                        expanded = if (isOpen) null else topic.title
                    }.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(topic.title, style = label(15.sp, 600), color = cs.onSurface)
                        Text(topic.summary, style = label(12.sp), color = cs.onSurfaceVariant)
                    }
                    Icon(Sym.ArrowDropDown, contentDescription = if (isOpen) "Collapse" else "Expand",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = arrowRotation })
                }
                AnimatedVisibility(isOpen) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))
                        topic.steps.forEachIndexed { index, step ->
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
                                Text("${index + 1}", style = label(12.sp, 650), color = cs.primary)
                                Text(step, style = label(12.5.sp), color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        HelpShortcut("Open thermal options", R.drawable.sym_device_thermostat) {
            if (DeviceSupport.thermalDaemon) CustomProfileListActivity.start(context)
            else context.startActivity(Intent(context, GlobalActivity::class.java))
        }
        if (DeviceSupport.thermalDaemon) {
            HelpShortcut("Open charging profiles", R.drawable.sym_battery_charging_full) {
                ChargingProfileListActivity.start(context)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HelpShortcut(title: String, icon: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh)
            .clickable(role = Role.Button) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                onClick()
            }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Glyph(icon, cs.primary, 17.dp)
        Text(title, style = label(11.5.sp, 550), color = cs.onSurface)
    }
}
