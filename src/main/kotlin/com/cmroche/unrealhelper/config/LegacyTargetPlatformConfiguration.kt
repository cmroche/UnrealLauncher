package com.cmroche.unrealhelper.config

import kotlinx.serialization.Serializable

@Serializable
internal data class LegacyTargetPlatformConfigurationsFile(
    val version: Int = 1,
    val configurations: List<LegacyTargetPlatformConfiguration> = emptyList(),
)

@Serializable
internal data class LegacyTargetPlatformConfiguration(
    val name: String,
    val entries: List<LegacyTargetPlatformEntry> = emptyList(),
)

@Serializable
internal data class LegacyTargetPlatformEntry(
    val targetType: String,
    val platform: String,
    val arguments: String = "",
    val executablePath: String = "",
    val workingDirectory: String = "",
)
