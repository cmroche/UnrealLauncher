package com.cmroche.unrealhelper.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class TargetPlatformConfigurationsFile(
    val version: Int = CurrentVersion,
    val configurations: List<TargetPlatformConfiguration> = emptyList(),
) {
    companion object {
        const val CurrentVersion = 2
    }
}

@Serializable
data class TargetPlatformConfiguration(
    val name: String,
    val entries: List<TargetPlatformEntry> = emptyList(),
)

@Serializable
data class TargetPlatformEntry(
    @Transient val targetType: String = "",
    val platform: String,
    val arguments: String = "",
    @Transient val executablePath: String = "",
    @Transient val workingDirectory: String = "",
    val targetName: String = "",
    val cookOnLaunch: Boolean = false,
    val incrementalCookOnLaunch: Boolean = false,
) {
    fun normalized(): TargetPlatformEntry =
        copy(
            platform = platform.trim(),
            arguments = arguments.trim(),
            targetName = targetName.trim(),
        )
}

fun TargetPlatformConfiguration.normalized(): TargetPlatformConfiguration =
    copy(
        name = name.trim(),
        entries = entries.map { it.normalized() },
    )

fun TargetPlatformConfigurationsFile.normalized(): TargetPlatformConfigurationsFile =
    copy(
        configurations = configurations.map { it.normalized() },
    )
