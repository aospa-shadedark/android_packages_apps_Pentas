/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.device

import android.content.Context
import android.os.Build
import android.os.SystemProperties
import java.io.File

data class ThermalZoneSpec(
    val index: Int,
    val type: String,
    val tempPath: String,
)

data class CpuClusterSpec(
    val policyIndex: Int,
    val policyDir: String,
    val cpuIds: List<Int>,
    val name: String,
    val hwMinKhz: Long,
    val hwMaxKhz: Long,
)

data class GpuSpec(
    val baseDir: String,
    val curFreqPaths: List<String>,
    val busyPercentPath: String?,
    val availableFreqsPath: String?,
    val devfreqMinPath: String?,
    val devfreqMaxPath: String?,
    val governorPath: String?,
    val availableGovernorsPath: String?,
    val busyPaths: List<String> = listOfNotNull(busyPercentPath),
    val minClockMhzPath: String? = null,
    val maxClockMhzPath: String? = null,
)

data class DeviceProfile(
    val model: String,
    val platform: String,
    val thermalZones: List<ThermalZoneSpec>,
    val cpuClusters: List<CpuClusterSpec>,
    val gpu: GpuSpec?,
    val batteryTempPath: String?,
    val batteryCurrentNowPath: String?,
    val batteryVoltageNowPath: String?,
    val batteryCapacityPath: String?,
    val batteryStatusPath: String?,
    val colorModeMap: Map<Int, Int>,
    val usesAospColorFallback: Boolean,
) {
    val cpuTempPaths: List<String>
        get() = thermalZones
            .filter {
                val t = it.type.lowercase()
                t.contains("cpu") || t.contains("core") || t.contains("cluster") ||
                    t.contains("big") || t.contains("little") || t.contains("prime")
            }
            .map { it.tempPath }

    val gpuTempPaths: List<String>
        get() = thermalZones
            .filter {
                val t = it.type.lowercase()
                t.contains("gpu") || t.contains("kgsl") || t.contains("adreno")
            }
            .map { it.tempPath }

    val ddrTempPath: String?
        get() = thermalZones
            .firstOrNull {
                val t = it.type.lowercase()
                t.contains("ddr") || t.contains("dram") || t.contains("mem")
            }
            ?.tempPath

    private val boardTempZone: ThermalZoneSpec?
        get() = thermalZones.firstOrNull {
            val type = it.type.lowercase()
            type.contains("board") || type.contains("pmic") || type.contains("batt_therm") ||
                type.startsWith("soc")
        }

    /** A board/PMIC sensor when the kernel publishes one; never substitute another zone by index. */
    val boardTempPath: String? get() = boardTempZone?.tempPath

    val boardTempLabel: String? get() = boardTempZone?.type?.lowercase()?.let { type ->
        when {
            type.contains("pmic") || type.startsWith("pm") -> "PMIC"
            type.contains("batt") -> "Battery"
            type.contains("soc") -> "SoC"
            else -> "Board"
        }
    }

    val primaryCpuPolicyDir: String?
        get() = cpuClusters.lastOrNull()?.policyDir

}

object DeviceProfiles {
    @Volatile
    private var instance: DeviceProfile? = null

    fun get(context: Context): DeviceProfile {
        return instance ?: synchronized(this) {
            instance ?: detect(context.applicationContext).also { instance = it }
        }
    }

    private fun detect(context: Context): DeviceProfile {
        val thermalZones = scanThermalZones()
        val cpuClusters = scanCpuClusters()
        val gpu = scanGpu()
        val battery = scanBattery(thermalZones)
        val color = detectColorModeMap(context)

        return DeviceProfile(
            model = Build.MODEL,
            platform = SystemProperties.get("ro.board.platform", "unknown"),
            thermalZones = thermalZones,
            cpuClusters = cpuClusters,
            gpu = gpu,
            batteryTempPath = battery.tempPath,
            batteryCurrentNowPath = battery.currentNowPath,
            batteryVoltageNowPath = battery.voltageNowPath,
            batteryCapacityPath = battery.capacityPath,
            batteryStatusPath = battery.statusPath,
            colorModeMap = color.first,
            usesAospColorFallback = color.second,
        )
    }

    private fun scanThermalZones(): List<ThermalZoneSpec> {
        val zones = mutableListOf<ThermalZoneSpec>()
        runCatching {
            val zoneDirs = File("/sys/class/thermal").listFiles()
                ?.filter { it.name.startsWith("thermal_zone") }
                ?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
                ?: emptyList()
            zoneDirs.forEach { dir ->
                val index = dir.name.removePrefix("thermal_zone").toIntOrNull() ?: return@forEach
                val typeFile = File(dir, "type")
                val tempFile = File(dir, "temp")
                if (!typeFile.exists() || !tempFile.exists()) return@forEach
                val type = runCatching { typeFile.readText().trim() }.getOrDefault("zone$index")
                zones.add(
                    ThermalZoneSpec(
                        index = index,
                        type = type,
                        tempPath = tempFile.absolutePath,
                    )
                )
            }
        }

        return zones
    }

    private fun scanCpuClusters(): List<CpuClusterSpec> {
        val result = mutableListOf<CpuClusterSpec>()
        runCatching {
            val base = File("/sys/devices/system/cpu/cpufreq")
            val policies = base.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("policy") }
                ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: 0 }
                ?: emptyList()

            policies.forEach { policyDir ->
                val policyIndex = policyDir.name.removePrefix("policy").toIntOrNull()
                    ?: return@forEach
                runCatching {
                    val affected = File(policyDir, "affected_cpus")
                        .takeIf { it.exists() }
                        ?.readText()
                        ?.trim()
                        ?.split(Regex("\\s+"))
                        ?.mapNotNull { it.toIntOrNull() }
                        ?: emptyList()

                    val min = File(policyDir, "cpuinfo_min_freq")
                        .takeIf { it.exists() }
                        ?.readText()
                        ?.trim()
                        ?.toLongOrNull()
                        ?: 0L

                    val max = File(policyDir, "cpuinfo_max_freq")
                        .takeIf { it.exists() }
                        ?.readText()
                        ?.trim()
                        ?.toLongOrNull()
                        ?: 0L

                    val name = affected.takeIf { it.isNotEmpty() }?.let {
                        "CPU ${it.minOrNull()}–${it.maxOrNull()}"
                    } ?: "CPU cluster ${policyIndex + 1}"

                    CpuClusterSpec(
                        policyIndex = policyIndex,
                        policyDir = policyDir.absolutePath,
                        cpuIds = affected,
                        name = name,
                        hwMinKhz = min,
                        hwMaxKhz = max,
                    )
                }.getOrNull()?.let(result::add)
            }
        }

        return result
    }

    private fun scanGpu(): GpuSpec? {
        fun files(dir: File?): List<File> =
            runCatching { dir?.listFiles()?.filter { it.isDirectory } ?: emptyList() }.getOrDefault(emptyList())
        fun exists(path: String): Boolean = runCatching { File(path).exists() }.getOrDefault(false)
        fun hasFrequencyNode(dir: File) = listOf(
            "cur_freq", "available_frequencies", "min_freq", "max_freq",
        ).any { exists("${dir.absolutePath}/$it") }

        val kgslLink = File("/sys/class/kgsl/kgsl-3d0").takeIf { it.exists() }
        val kgslRoot = kgslLink?.let { runCatching { it.canonicalFile }.getOrDefault(it) }
        val kgslDevice = kgslRoot?.parentFile?.parentFile
        val classDevfreq = files(File("/sys/class/devfreq"))
        val deviceDevfreq = files(kgslDevice?.let { File(it, "devfreq") })
        val devfreqDirs = (deviceDevfreq + classDevfreq).distinctBy {
            runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
        }
        val kgslDevicePath = kgslDevice?.let { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        val devfreq = devfreqDirs.firstOrNull { dir ->
            val path = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            val isKgslChild = kgslDevicePath != null && path.startsWith("$kgslDevicePath/")
            val looksLikeGpu = dir.name.contains("gpu", true) || dir.name.contains("kgsl", true) ||
                dir.name.contains("3d0", true)
            (isKgslChild || looksLikeGpu) && hasFrequencyNode(dir)
        }
        val rootFile = kgslRoot ?: devfreq ?: return null
        val root = rootFile.absolutePath
        val devfreqRoot = devfreq?.absolutePath

        fun existing(vararg candidates: String): String? =
            candidates.firstOrNull(::exists)

        // KGSL controls and its devfreq child are siblings on some Qualcomm kernels.
        // Resolve the class symlink before looking for that child.
        val busyPaths = listOfNotNull(
            existing("$root/gpu_busy_percentage"),
            existing("$root/gpubusy"),
            existing("$root/load"),
        ).distinct()
        val curFreqPaths = listOfNotNull(
            existing("$root/gpuclk"),
            devfreqRoot?.let { existing("$it/cur_freq") },
            existing("$root/devfreq/cur_freq", "$root/cur_freq"),
        ).distinct()
        return GpuSpec(
            baseDir = root,
            curFreqPaths = curFreqPaths,
            busyPercentPath = busyPaths.firstOrNull(),
            availableFreqsPath = existing(
                "$root/gpu_available_frequencies",
                *(devfreqRoot?.let { arrayOf("$it/available_frequencies") } ?: emptyArray()),
                "$root/devfreq/available_frequencies",
                "$root/available_frequencies",
            ),
            devfreqMinPath = existing(
                *(devfreqRoot?.let { arrayOf("$it/min_freq") } ?: emptyArray()),
                "$root/devfreq/min_freq", "$root/min_freq",
            ),
            devfreqMaxPath = existing(
                *(devfreqRoot?.let { arrayOf("$it/max_freq") } ?: emptyArray()),
                "$root/devfreq/max_freq", "$root/max_freq",
            ),
            governorPath = existing(
                *(devfreqRoot?.let { arrayOf("$it/governor") } ?: emptyArray()),
                "$root/devfreq/governor", "$root/governor",
            ),
            availableGovernorsPath = existing(
                *(devfreqRoot?.let { arrayOf("$it/available_governors") } ?: emptyArray()),
                "$root/devfreq/available_governors",
                "$root/available_governors",
            ),
            busyPaths = busyPaths,
            minClockMhzPath = existing("$root/min_clock_mhz"),
            maxClockMhzPath = existing("$root/max_clock_mhz"),
        )
    }

    private fun scanBattery(thermalZones: List<ThermalZoneSpec>): BatterySpec {
        // The fuel gauge reads the cell; the batt_therm zone tracks the board. The zone is a fallback only.
        val zoneFallbackPath =
            thermalZones.firstOrNull { it.type.equals("battery", ignoreCase = true) }?.tempPath
                ?: thermalZones.firstOrNull { it.type.equals("batt_therm", ignoreCase = true) }?.tempPath

        val battery = File("/sys/class/power_supply/battery")
        if (battery.exists()) {
            return BatterySpec(
                File(battery, "temp").takeIf { it.exists() }?.absolutePath ?: zoneFallbackPath,
                File(battery, "current_now").takeIf { it.exists() }?.absolutePath,
                File(battery, "voltage_now").takeIf { it.exists() }?.absolutePath,
                File(battery, "capacity").takeIf { it.exists() }?.absolutePath,
                File(battery, "status").takeIf { it.exists() }?.absolutePath,
            )
        }

        runCatching {
            val base = File("/sys/class/power_supply")
            val candidate = base.listFiles()?.firstOrNull { dir ->
                val type = File(dir, "type").takeIf { it.exists() }?.readText()?.trim()
                type.equals("Battery", ignoreCase = true)
            }
            if (candidate != null) {
                return BatterySpec(
                    File(candidate, "temp").takeIf { it.exists() }?.absolutePath ?: zoneFallbackPath,
                    File(candidate, "current_now").takeIf { it.exists() }?.absolutePath,
                    File(candidate, "voltage_now").takeIf { it.exists() }?.absolutePath,
                    File(candidate, "capacity").takeIf { it.exists() }?.absolutePath,
                    File(candidate, "status").takeIf { it.exists() }?.absolutePath,
                )
            }
        }

        return BatterySpec(zoneFallbackPath, null, null, null, null)
    }

    private fun detectColorModeMap(context: Context): Pair<Map<Int, Int>, Boolean> {
        val vendorMap = mapOf(
            0 to 269, // Original
            1 to 258, // Vivid
            2 to 256, // Saturated
            3 to 268, // P3
            4 to 267, // sRGB
        )

        val aospMap = mapOf(
            0 to 0, // Natural
            1 to 1, // Boosted
            2 to 2, // Saturated
            3 to 3, // Automatic
            4 to 0, // sRGB-ish fallback
        )

        val allowed = runCatching {
            context.resources.getIntArray(com.android.internal.R.array.config_availableColorModes).toSet()
        }.getOrDefault(emptySet())

        val hasVendorModes = allowed.any { it >= 256 }

        return if (hasVendorModes) {
            vendorMap.filterValues { it in allowed } to false
        } else {
            aospMap.filterKeys { it != 4 }.filterValues { it in allowed } to true
        }
    }

    private data class BatterySpec(
        val tempPath: String?,
        val currentNowPath: String?,
        val voltageNowPath: String?,
        val capacityPath: String?,
        val statusPath: String?,
    )
}
