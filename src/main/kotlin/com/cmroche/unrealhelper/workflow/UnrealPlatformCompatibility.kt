package com.cmroche.unrealhelper.workflow

import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.config.resolveConfigurationEntries
import com.cmroche.unrealhelper.discovery.UnrealTargetType
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState

data class UnrealIdeConfigurationAndPlatform(
    val configuration: String,
    val platform: String,
)

fun incompatiblePlatformErrors(
    configuration: TargetPlatformConfiguration,
    state: UnrealHelperSettingsState,
    availableConfigurations: Collection<UnrealIdeConfigurationAndPlatform>?,
): List<String> {
    if (availableConfigurations.isNullOrEmpty()) return emptyList()

    return resolveConfigurationEntries(configuration, state).entries
        .filterNot { entry ->
            val riderConfiguration = riderSolutionConfiguration(state.buildConfiguration, entry.targetType)
            availableConfigurations.any { available ->
                available.configuration.equals(riderConfiguration, ignoreCase = true) &&
                    platformMatches(entry.platform, available.platform)
            }
        }
        .map { entry ->
            "Entry ${entry.index + 1} ${entry.targetName} / ${entry.platform}: " +
                "platform '${entry.platform}' is incompatible with target type '${entry.targetType}' and " +
                "build configuration '${state.buildConfiguration}' in the current Rider environment"
        }
}

internal fun riderSolutionConfiguration(buildConfiguration: String, targetType: String): String =
    if (targetType == UnrealTargetType.Game.name) {
        buildConfiguration
    } else {
        "$buildConfiguration $targetType"
    }

private fun platformMatches(requestedPlatform: String, availablePlatform: String): Boolean =
    availablePlatform.equals(requestedPlatform, ignoreCase = true) ||
        availablePlatform.startsWith("$requestedPlatform-", ignoreCase = true)
