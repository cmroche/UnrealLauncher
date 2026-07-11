package com.cmroche.unrealhelper.config

import com.cmroche.unrealhelper.settings.UnrealTargetState

internal fun migrateLegacyTargetPlatformFile(
    legacy: LegacyTargetPlatformConfigurationsFile,
    discoveredTargets: List<UnrealTargetState>,
): TargetPlatformConfigurationsFile =
    TargetPlatformConfigurationsFile(
        configurations = legacy.configurations.map { configuration ->
            TargetPlatformConfiguration(
                name = configuration.name,
                entries = configuration.entries.map { entry ->
                    TargetPlatformEntry(
                        targetName = migratedTargetName(entry.targetType, discoveredTargets),
                        platform = entry.platform,
                        arguments = entry.arguments,
                    )
                },
            )
        },
    ).normalized()

internal fun migratedTargetName(type: String, targets: List<UnrealTargetState>): String {
    val matches = targets.filter { it.type.trim() == type.trim() }
    return when (matches.size) {
        1 -> matches.single().name.trim()
        0 -> "Missing legacy ${type.trim()} target"
        else -> ""
    }
}
