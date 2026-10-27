/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.charging

import androidx.compose.ui.res.vectorResource

import org.yasvara.pentas.ui.kit.Sym

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yasvara.pentas.ui.settings.SettingsIntSliderRow
import org.yasvara.pentas.ui.settings.SettingsValueSlider
import org.yasvara.pentas.ui.theme.PentasTheme
import org.yasvara.pentas.device.DeviceSupport
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint(ComponentActivity::class)
class ChargingProfileEditorActivity : Hilt_ChargingProfileEditorActivity() {

    private val vm: ChargingProfileEditorViewModel by viewModels()
    @Inject lateinit var repository: ChargingProfileRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PentasTheme {
                EditorRoot(onBack = { finish() })
            }
        }
    }

    /** Reverts a test-applied draft when the editor finishes. */
    override fun onDestroy() {
        if (isFinishing) vm.revertPreview()
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EditorRoot(onBack: () -> Unit) {
        val snackbar = remember { SnackbarHostState() }
        var showImporter by remember { mutableStateOf(false) }

        LaunchedEffect(vm.statusMessage) {
            vm.statusMessage?.let { snackbar.showSnackbar(it); vm.clearStatus() }
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(if (vm.isExisting) "Edit Charging Profile"
                             else "New Charging Profile",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Sym.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.applyNow() }) {
                            Icon(Sym.Bolt, "Test apply")
                        }
                        IconButton(onClick = { if (vm.save()) onBack() }) {
                            Icon(Sym.Check, "Save")
                        }
                    },
                )
            },
        ) { pad ->
            Column(
                Modifier.fillMaxSize()
                    .padding(pad),
            ) {
                Column(
                    Modifier.weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    Spacer(Modifier.height(8.dp))
                    NameField()
                    PresetRow(onImport = { showImporter = true })
                    WizardCard()
                    SectionLabel("Tiers (${vm.tiers.size}/${ChargingProfiles.MAX_TIERS})")
                    ChargingCurvePreview(
                        tiers = vm.tiers.toList(),
                        suspendMc = vm.suspendMc,
                    )
                    Spacer(Modifier.height(8.dp))
                    vm.tiers.forEachIndexed { i, tier ->
                        TierCard(
                            index = i,
                            tier = tier,
                            onChange = { vm.updateTier(i, it) },
                            onRemove = { vm.removeTier(i) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    AddTierButton(onClick = { vm.addTier() })
                    SectionLabel("Emergency Protection")
                    EmergencyCard()
                    SectionLabel("Charging Policy")
                    PolicyCard()
                    ErrorList()
                    Spacer(Modifier.height(16.dp))
                }
                // bottom action bar
                Row(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { vm.applyNow() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Sym.Bolt, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp)); Text("Test")
                    }
                    Button(
                        onClick = { if (vm.saveAndApply()) onBack() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Save & Activate") }
                }
            }
        }

        if (showImporter) {
            ImporterSheet(
                repository = repository,
                onPick = { vm.importFrom(it); showImporter = false },
                onDismiss = { showImporter = false },
            )
        }
    }

    // ---- components ----

    @Composable
    private fun NameField() {
        OutlinedTextField(
            value = vm.name,
            onValueChange = { vm.name = it; vm.dirty = true },
            label = { Text("Profile name") },
            placeholder = { Text("e.g. Night Charging") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }

    @Composable
    private fun PresetRow(onImport: () -> Unit) {
        SectionLabel("Quick Start")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChargingSuggestionEngine.PRESETS.take(2).forEach { p ->
                PresetChip(p, Modifier.weight(1f)) { vm.applyPreset(p) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChargingSuggestionEngine.PRESETS.drop(2).forEach { p ->
                PresetChip(p, Modifier.weight(1f)) { vm.applyPreset(p) }
            }
            OutlinedButton(
                onClick = onImport,
                modifier = Modifier.weight(1f),
            ) { Text("More…", fontSize = 11.sp) }
        }
    }

    @Composable
    private fun PresetChip(
        preset: ChargingSource.Preset,
        modifier: Modifier,
        onClick: () -> Unit,
    ) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(14.dp),
            onClick = onClick,
        ) {
            Column(Modifier.padding(12.dp)) {
                Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(preset.icon), null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(6.dp))
                Text(preset.name, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(preset.description, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
            }
        }
    }

    @Composable
    private fun WizardCard() {
        var open by remember { mutableStateOf(false) }
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Smart Wizard", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { open = !open }) {
                        Text(if (open) "Hide" else "Tune")
                    }
                }
                if (open) {
                    val cfg = vm.wizardConfig
                    SettingsValueSlider(
                        label = "Target temp",
                        value = cfg.targetTempC,
                        range = 30f..48f,
                        onCommit = { vm.wizardConfig = cfg.copy(targetTempC = it) },
                        valueText = { "${it.roundToInt()}°C" }
                    )
                    SettingsValueSlider(
                        label = "Max current",
                        value = cfg.maxWatt,
                        range = ChargingProfiles.MIN_WATT..ChargingProfiles.MAX_WATT,
                        onCommit = { vm.wizardConfig = cfg.copy(maxWatt = it) },
                        valueText = { "%.0fW".format(it) }
                    )
                    SettingsValueSlider(
                        label = "Aggressiveness",
                        value = cfg.aggressiveness.toFloat(),
                        range = 0f..100f,
                        onCommit = { vm.wizardConfig = cfg.copy(aggressiveness = it.roundToInt()) },
                        valueText = { if (it > 50) "Cooler" else "Faster" }
                    )
                    Button(
                        onClick = { vm.generateFromWizard() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Generate Tiers") }
                }
            }
        }
    }

    @Composable
    private fun TierCard(
        index: Int,
        tier: ChargingTier,
        onChange: (ChargingTier) -> Unit,
        onRemove: () -> Unit,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tier ${index + 1}" + if (tier.isPureCap) "  (cap)" else "",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f))
                    Text(
                        "${ChargingProfiles.mcToC(tier.trigMc).let { "%.0f".format(it) }}°C → ${"%.1f".format(ChargingProfiles.uaToWatt(tier.maxUa))}W",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                        Icon(Sym.Delete, "Remove",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp))
                    }
                }
                SettingsValueSlider(
                    label = "Trigger",
                    value = ChargingProfiles.mcToC(tier.trigMc),
                    range = 25f..58f,
                    onCommit = { onChange(tier.copy(trigMc = ChargingProfiles.cToMc(it), clrMc = if (tier.clrMc >= ChargingProfiles.cToMc(it)) ChargingProfiles.cToMc(it) - 1500 else tier.clrMc)) },
                    valueText = { "%.1f°C".format(it) }
                )
                SettingsValueSlider(
                    label = "Clear",
                    value = ChargingProfiles.mcToC(tier.clrMc),
                    range = 25f..58f,
                    onCommit = { onChange(tier.copy(clrMc = ChargingProfiles.cToMc(it))) },
                    valueText = { "%.1f°C".format(it) }
                )
                SettingsValueSlider(
                    label = "PID target",
                    value = ChargingProfiles.mcToC(tier.targetMc),
                    range = 0f..58f,
                    onCommit = { onChange(tier.copy(targetMc = if (it < 0.5f) 0L else ChargingProfiles.cToMc(it))) },
                    valueText = { if (it < 0.5f) "OFF (cap)" else "%.1f°C".format(it) }
                )
                SettingsValueSlider(
                    label = "Max current",
                    value = ChargingProfiles.uaToWatt(tier.maxUa),
                    range = ChargingProfiles.MIN_WATT..ChargingProfiles.MAX_WATT,
                    onCommit = { onChange(tier.copy(maxUa = ChargingProfiles.wattToUa(it), minUa = tier.minUa.coerceAtMost(ChargingProfiles.wattToUa(it)))) },
                    valueText = { "%.1fW".format(it) }
                )
                SettingsValueSlider(
                    label = "Min current",
                    value = ChargingProfiles.uaToWatt(tier.minUa),
                    range = ChargingProfiles.MIN_WATT..ChargingProfiles.MAX_WATT,
                    onCommit = { onChange(tier.copy(minUa = ChargingProfiles.wattToUa(it))) },
                    valueText = { "%.1fW".format(it) }
                )
                var adv by remember { mutableStateOf(false) }
                TextButton(onClick = { adv = !adv }) {
                    Text(if (adv) "Hide PID gains" else "PID gains…", fontSize = 11.sp)
                }
                if (adv) {
                    SettingsIntSliderRow("Kp", tier.ks.toInt(), 0..400, { onChange(tier.copy(ks = it.toLong())) })
                    SettingsIntSliderRow("Ki", tier.ki.toInt(), 0..100, { onChange(tier.copy(ki = it.toLong())) })
                    SettingsIntSliderRow("Kd", tier.kc.toInt(), 0..300, { onChange(tier.copy(kc = it.toLong())) })
                }
            }
        }
    }

    @Composable
    private fun EmergencyCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(12.dp)) {
                SettingsValueSlider(
                    label = "Suspend charging at",
                    value = ChargingProfiles.mcToC(vm.suspendMc),
                    range = 25f..58f,
                    onCommit = {
                        val mc = ChargingProfiles.cToMc(it)
                        vm.suspendMc = mc
                        if (vm.resumeMc >= mc) vm.resumeMc = mc - 3000
                        vm.dirty = true
                    },
                    valueText = { "%.1f°C".format(it) }
                )
                SettingsValueSlider(
                    label = "Resume below",
                    value = ChargingProfiles.mcToC(vm.resumeMc),
                    range = 25f..58f,
                    onCommit = { vm.resumeMc = ChargingProfiles.cToMc(it); vm.dirty = true },
                    valueText = { "%.1f°C".format(it) }
                )
            }
        }
    }

    /** Hot ceiling and input limits; a preset fills in the rest of the tables. */
    @Composable
    private fun PolicyCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "Stock policy stops fast charging at 48 °C and drops the float " +
                        "voltage below the cutoff gate, which latches charging off " +
                        "until the pack loses ~400 mV.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChargingPolicyPresets.Preset.entries
                        .filter {
                            DeviceSupport.bypassCharging || it != ChargingPolicyPresets.Preset.UNRESTRICTED
                        }
                        .forEach { preset ->
                            PolicyPresetChip(preset, Modifier.weight(1f))
                        }
                }

                if (vm.policy.isEmpty) {
                    Text(
                        "Using the kernel's device-tree policy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    return@Column
                }

                Spacer(Modifier.height(6.dp))
                SettingsValueSlider(
                    label = "Full-speed charging up to",
                    value = vm.hotCeilingC.toFloat(),
                    range = 40f..(ChargingEnvelope.TEMP_MAX_C - 2).toFloat(),
                    onCommit = { vm.setHotCeiling(it.toInt()) },
                    valueText = { "%.0f°C".format(it) },
                )
                Text(
                    "Above this the pack stops charging. The safety envelope caps " +
                        "it at ${ChargingEnvelope.TEMP_MAX_C - 2} °C.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(10.dp))
                CapRow(
                    label = "USB-PD input limit",
                    value = vm.policy.caps.inputMa["pd"] ?: 1600,
                    max = ChargingEnvelope.IIN_MAX_MA,
                    hint = if (DeviceSupport.bypassCharging) {
                        "Stock 1600 mA. Too low and bypass cannot carry the phone."
                    } else "Stock 1600 mA.",
                ) { ma ->
                    vm.updatePolicy { p ->
                        p.copy(caps = p.caps.copy(inputMa = p.caps.inputMa + ("pd" to ma)))
                    }
                }

                Spacer(Modifier.height(10.dp))
                if (DeviceSupport.bypassCharging) BypassRow()

                Spacer(Modifier.height(12.dp))
                SensorRow()
            }
        }
    }

    @Composable
    private fun PolicyPresetChip(
        preset: ChargingPolicyPresets.Preset,
        modifier: Modifier = Modifier,
    ) {
        Column(
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                .clickable { vm.applyPolicyPreset(preset) }
                .padding(10.dp),
        ) {
            Text(preset.label, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
            Text(preset.summary(DeviceSupport.bypassCharging), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun CapRow(
        label: String,
        value: Int,
        max: Int,
        hint: String,
        onCommit: (Int) -> Unit,
    ) {
        SettingsValueSlider(
            label = label,
            value = value.toFloat(),
            range = 500f..max.toFloat(),
            onCommit = { onCommit(it.toInt()) },
            valueText = { "%.0f mA".format(it) },
        )
        Text(hint, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    @Composable
    private fun BypassRow() {
        val bypass = vm.policy.bypass ?: BypassTuning()
        Text("Bypass charging", style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold)
        Text(
            "Runs the phone straight off the adapter and holds the pack at " +
                "${bypass.targetMa} mA, so gaming heat does not go through the cell.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BypassMode.entries.forEach { mode ->
                val selected = bypass.mode == mode
                Text(
                    mode.label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        .clickable {
                            vm.updatePolicy { it.copy(bypass = bypass.copy(mode = mode)) }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }

    /** Sensor roles with live readings. */
    @Composable
    private fun SensorRow() {
        LaunchedEffect(Unit) {
            while (true) {
                vm.refreshReadings()
                kotlinx.coroutines.delay(2000)
            }
        }
        val r = vm.readings
        Text("Sensors", style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold)
        SensorLine("Cell (fuel gauge)", vm.policy.dynamics.cellSensor, r.cellC)
        SensorLine("Board", vm.policy.dynamics.boardSensor, r.boardC)
        SensorLine("Charge pump", vm.policy.dynamics.pumpSensor, r.pumpC)
        r.boardOffsetC?.let { delta ->
            Text(
                "Board reads %+.1f °C against the cell — thresholds are on the cell scale."
                    .format(delta),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    @Composable
    private fun SensorLine(role: String, sensor: String, value: Float?) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("$role  ·  $sensor", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value?.let { "%.1f°C".format(it) } ?: "—",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun ErrorList() {
        if (vm.validationErrors.isEmpty()) return
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(12.dp)) {
                vm.validationErrors.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(vertical = 1.dp))
                }
            }
        }
    }

    @Composable
    private fun AddTierButton(onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Sym.Add, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add tier", color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp),
        )
    }



    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ImporterSheet(
        repository: ChargingProfileRepository,
        onPick: (ChargingSource) -> Unit,
        onDismiss: () -> Unit,
    ) {
        ModalBottomSheet(onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Text("Start from", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            Column(Modifier.padding(horizontal = 10.dp).padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState())) {
                SheetLabel("Presets")
                ChargingSuggestionEngine.PRESETS.forEach { p ->
                    SourceRow(p.name, p.description) { onPick(p) }
                }
                SheetLabel("Vendor templates")
                ChargingSuggestionEngine.VENDOR_TEMPLATES.forEach { v ->
                    SourceRow(v.name, v.description) { onPick(v) }
                }
                SheetLabel("My profiles (copy)")
                repository.loadAll().forEach { p ->
                    SourceRow(p.name, "${p.tiers.size} tiers") {
                        onPick(ChargingSource.Vendor(p.id, p.name, "copy", p.tiers.toList()))
                    }
                }
            }
        }
    }

    @Composable
    private fun SheetLabel(text: String) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 4.dp))
    }

    @Composable
    private fun SourceRow(title: String, subtitle: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
