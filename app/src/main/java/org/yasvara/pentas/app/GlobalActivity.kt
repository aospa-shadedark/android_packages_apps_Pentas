/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.app

import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.yasvara.pentas.charging.ChargingProfileListActivity
import org.yasvara.pentas.charging.ChargingProfileRepository
import org.yasvara.pentas.device.DeviceSupport
import org.yasvara.pentas.thermal.PerfTuner
import org.yasvara.pentas.thermal.ThermalProfiles
import org.yasvara.pentas.thermal.custom.CustomProfileListActivity
import org.yasvara.pentas.ui.kit.Block
import org.yasvara.pentas.ui.kit.Kit
import org.yasvara.pentas.ui.kit.ListItem
import org.yasvara.pentas.ui.kit.Section
import org.yasvara.pentas.ui.kit.SwitchRow
import org.yasvara.pentas.ui.kit.Sym
import org.yasvara.pentas.ui.theme.PentasTheme
import org.yasvara.pentas.ui.tuning.ClockEditor

/** The system-wide baseline every game starts from: clocks, thermal scene, charging. */
class GlobalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PentasTheme { Global(::finish) } }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GlobalDeps {
    fun perf(): PerfTuner
    fun charging(): ChargingProfileRepository
}

@Composable
private fun Global(finish: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val cr = ctx.contentResolver
    val deps = remember { EntryPointAccessors.fromApplication(ctx.applicationContext, GlobalDeps::class.java) }
    val perf = deps.perf()
    val charging = deps.charging()
    var clocks by remember { mutableStateOf(perf.loadGlobalProfile()) }
    var thermal by remember { mutableIntStateOf(Settings.System.getIntForUser(cr, ThermalProfiles.KEY_THERMAL_PROFILE, 0, UserHandle.USER_CURRENT)) }
    var curve by remember { mutableStateOf(charging.getGlobalProfileId()) }
    var bypass by remember { mutableStateOf(Settings.System.getIntForUser(cr, ThermalProfiles.KEY_BYPASS_CHARGING, 0, UserHandle.USER_CURRENT) == 1) }
    Screen("Global", onBack = finish) {
        Section("Clocks")
        ClockEditor(perf, clocks, warningKey = "global") { clocks = it; perf.saveGlobalProfile(it) }

        Section("Thermal")
        val options = remember {
            ThermalProfiles.options(Settings.System.getStringForUser(cr, ThermalProfiles.KEY_CUSTOM_PROFILES, UserHandle.USER_CURRENT))
        }
        val daemon = DeviceSupport.thermalDaemon
        val thermalRows = options.size + if (daemon) 1 else 0
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEachIndexed { i, o ->
                ListItem(o.name, Kit.connected(i, thermalRows, 24.dp, 6.dp), Modifier.height(52.dp),
                    selected = o.index == thermal,
                    trailing = if (o.index == thermal) ({ Icon(Sym.Check, null, tint = cs.onSecondaryContainer) }) else null,
                    onClick = {
                        thermal = o.index
                        Settings.System.putIntForUser(cr, ThermalProfiles.KEY_THERMAL_PROFILE, o.index, UserHandle.USER_CURRENT)
                    })
            }
            if (daemon) NavRow(Sym.Add, "Custom profiles", null, options.size, thermalRows) { CustomProfileListActivity.start(ctx) }
        }

        if (daemon) {
            Section("Charging")
            val curves = remember { charging.loadAll() }
            val ids = listOf<String?>(null) + curves.map { it.id }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ids.forEachIndexed { i, id ->
                    ListItem(curves.firstOrNull { it.id == id }?.name ?: "Vendor default", Kit.connected(i, ids.size + 1, 24.dp, 6.dp),
                        Modifier.height(52.dp), selected = id == curve,
                        trailing = if (id == curve) ({ Icon(Sym.Check, null, tint = cs.onSecondaryContainer) }) else null,
                        onClick = { curve = id; charging.setGlobalProfileId(id); charging.applyActiveProfile(null) })
                }
                NavRow(Sym.Add, "Charging curves", null, ids.size, ids.size + 1) { ChargingProfileListActivity.start(ctx) }
            }
        }
        if (DeviceSupport.bypassCharging) Block {
            SwitchRow("Bypass charging", bypass, {
                bypass = it
                Settings.System.putIntForUser(cr, ThermalProfiles.KEY_BYPASS_CHARGING, if (it) 1 else 0, UserHandle.USER_CURRENT)
            })
        }
    }
}
