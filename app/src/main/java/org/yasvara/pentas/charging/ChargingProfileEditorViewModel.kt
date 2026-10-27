/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.charging

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import org.yasvara.pentas.device.DeviceSupport

@HiltViewModel
class ChargingProfileEditorViewModel @Inject constructor(
    val repository: ChargingProfileRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val profileId: String =
        savedStateHandle.get<String>("profile_id") ?: CustomChargingProfile.newId()
    val isExisting: Boolean = savedStateHandle.get<String>("profile_id") != null
    private val sourceId: String? = savedStateHandle.get<String>("source_id")

    var name by mutableStateOf("")
    val tiers = mutableStateListOf<ChargingTier>()
    // Cell scale — see CustomChargingProfile for why these are not 48/45.
    var suspendMc by mutableLongStateOf(52_000L)
    var resumeMc by mutableLongStateOf(48_000L)

    /**
     * The limits the tiers run inside. Empty = leave the kernel at its
     * device-tree policy, which is what every pre-existing profile means.
     */
    var policy by mutableStateOf(ChargingPolicy())
    /** Live sensor values, refreshed by the editor so sensor pickers show truth. */
    var readings by mutableStateOf(ChargeReadings.read(ChargingPolicy().dynamics))

    var dirty by mutableStateOf(false)
    /** True while the daemon is running an uncommitted draft from [applyNow]. */
    var previewApplied by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)
    var seededFrom by mutableStateOf<String?>(null)
    val validationErrors = mutableStateListOf<String>()

    // wizard config (live)
    var wizardConfig by mutableStateOf(ChargingConfig())

    init {
        refreshAndLoad()
    }

    private fun refreshAndLoad() {
        val existing = repository.getById(profileId)
        if (existing != null) {
            loadFrom(existing)
            return
        }
        val source = sourceId?.let { id ->
            ChargingSuggestionEngine.PRESETS.firstOrNull { it.id == id }
                ?: ChargingSuggestionEngine.VENDOR_TEMPLATES.firstOrNull { it.id == id }
                ?: repository.getById(id)?.let { p ->
                    ChargingSource.Vendor(p.id, p.name, "copy", p.tiers.toList())
                }
        }
        if (source != null) {
            importFrom(source)
        } else {
            tiers.add(ChargingTier())
        }
    }

    private fun loadFrom(p: CustomChargingProfile) {
        name = p.name
        suspendMc = p.emergencySuspendMc
        resumeMc = p.emergencyResumeMc
        policy = p.policy
        tiers.clear()
        tiers.addAll(p.tiers)
        refreshReadings()
    }

    fun refreshReadings() {
        readings = ChargeReadings.read(policy.dynamics)
    }

    /** Replace the whole policy from a named starting point. */
    fun applyPolicyPreset(preset: ChargingPolicyPresets.Preset) {
        policy = preset.build(DeviceSupport.bypassCharging)
        dirty = true
        refreshReadings()
        statusMessage = "${preset.label} policy loaded"
    }

    fun updatePolicy(block: (ChargingPolicy) -> ChargingPolicy) {
        policy = block(policy)
        dirty = true
    }

    /** Highest temperature still charging at full current, read from the band table. */
    val hotCeilingC: Int
        get() = policy.jeitaNormal.lastOrNull { !it.isCutoff }?.tHiC ?: 48

    /** Moves the hot ceiling in both band tables, keeping the float voltage. */
    fun setHotCeiling(c: Int) {
        val clamped = c.coerceIn(40, ChargingEnvelope.TEMP_MAX_C - 2)
        updatePolicy { p ->
            val base = p.takeIf { it.jeitaNormal.isNotEmpty() }
                ?: ChargingPolicyPresets.balanced()
            p.copy(
                jeitaNormal = reopen(base.jeitaNormal, clamped),
                jeitaFfc = reopen(base.jeitaFfc, clamped),
                hotFvGateMv = p.hotFvGateMv ?: 4200,
            )
        }
    }

    private fun reopen(bands: List<JeitaBand>, ceilingC: Int): List<JeitaBand> {
        if (bands.isEmpty()) return bands
        val lastCharging = bands.lastOrNull { !it.isCutoff } ?: return bands
        val kept = bands.filter { !it.isCutoff && it.tHiC <= 40 }
        return kept + listOf(
            lastCharging.copy(tLoC = 40, tHiC = ceilingC, hysLo = 1, hysHi = 0),
            JeitaBand(ceilingC, JeitaBand.SENTINEL_HI, 2, 2, 0, lastCharging.fvMv,
                lastCharging.itermMa),
        )
    }

    fun importFrom(source: ChargingSource) {
        when (source) {
            is ChargingSource.Preset -> {
                wizardConfig = source.config
                val gen = ChargingSuggestionEngine.generateProfile(source.config, source.name, profileId)
                loadFrom(gen)
                if (name.isBlank()) name = source.name
            }
            is ChargingSource.Vendor -> {
                tiers.clear()
                tiers.addAll(source.tiers)
                if (name.isBlank()) name = source.name
            }
        }
        seededFrom = source.name
        dirty = true
        statusMessage = "Imported from \"${source.name}\""
    }

    fun addTier() {
        if (tiers.size >= ChargingProfiles.MAX_TIERS) return
        val last = tiers.lastOrNull()
        tiers.add(
            last?.let {
                ChargingTier(
                    trigMc = it.trigMc + 2000, clrMc = it.trigMc,
                    targetMc = it.trigMc + 1000,
                    maxUa = (it.maxUa * 0.6f).toLong().coerceAtLeast(ChargingProfiles.FCC_HARD_MIN_UA),
                    minUa = (it.minUa * 0.6f).toLong().coerceAtLeast(ChargingProfiles.FCC_HARD_MIN_UA),
                    ks = it.ks, ki = it.ki, kc = it.kc,
                )
            } ?: ChargingTier()
        )
        dirty = true
    }

    fun removeTier(index: Int) {
        if (index in tiers.indices) { tiers.removeAt(index); dirty = true }
    }

    fun updateTier(index: Int, tier: ChargingTier) {
        if (index in tiers.indices) { tiers[index] = tier; dirty = true }
    }

    fun applyPreset(preset: ChargingSource.Preset) {
        importFrom(preset)
    }

    fun generateFromWizard() {
        val gen = ChargingSuggestionEngine.generateProfile(
            wizardConfig, name.ifBlank { "Custom" }, profileId
        )
        tiers.clear(); tiers.addAll(gen.tiers)
        suspendMc = gen.emergencySuspendMc
        resumeMc = gen.emergencyResumeMc
        dirty = true
        statusMessage = "Tiers generated from wizard"
    }

    fun toProfile() = CustomChargingProfile(
        id = profileId, name = name.trim(),
        tiers = tiers.toList(),
        emergencySuspendMc = suspendMc, emergencyResumeMc = resumeMc,
        policy = policy,
    )

    fun validate(): List<String> {
        val errs = repository.validate(toProfile())
        validationErrors.clear(); validationErrors.addAll(errs)
        return errs
    }

    fun save(): Boolean {
        if (validate().isNotEmpty()) { statusMessage = "Fix validation errors first"; return false }
        repository.save(toProfile())
        dirty = false
        statusMessage = "Profile saved"
        return true
    }

    fun saveAndApply(): Boolean {
        if (!save()) return false
        repository.setGlobalProfileId(profileId)
        repository.applyToDaemon(toProfile())
        previewApplied = false // this draft IS the selection now
        statusMessage = "Saved & set as active"
        return true
    }

    fun applyNow(): Boolean {
        if (validate().isNotEmpty()) { statusMessage = "Fix validation errors first"; return false }
        repository.applyToDaemon(toProfile())
        previewApplied = true
        statusMessage = "Applied for testing"
        return true
    }

    /** Restores the selected profile after a test-applied draft. */
    fun revertPreview() {
        if (!previewApplied) return
        previewApplied = false
        repository.applyActiveProfile(null)
    }

    fun clearStatus() { statusMessage = null }
}
