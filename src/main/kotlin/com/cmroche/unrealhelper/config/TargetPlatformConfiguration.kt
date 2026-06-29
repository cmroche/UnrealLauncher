package com.cmroche.unrealhelper.config

import kotlinx.serialization.Serializable

@Serializable
data class TargetPlatformConfigurationsFile(
    val version: Int = CurrentVersion,
    val configurations: List<TargetPlatformConfiguration> = emptyList(),
) {
    companion object {
        const val CurrentVersion = 1
    }
}

@Serializable
data class TargetPlatformConfiguration(
    val name: String,
    val entries: List<TargetPlatformEntry> = emptyList(),
)

@Serializable
data class TargetPlatformEntry(
    val targetType: String,
    val platform: String,
    val arguments: String = "",
    val executablePath: String = "",
    val workingDirectory: String = "",
) {
    fun normalized(): TargetPlatformEntry =
        copy(
            targetType = targetType.trim(),
            platform = platform.trim(),
            arguments = arguments.trim(),
            executablePath = executablePath.trim(),
            workingDirectory = workingDirectory.trim(),
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
