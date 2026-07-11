package com.cmroche.unrealhelper.config

import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.cmroche.unrealhelper.discovery.UnrealTargetType
import java.nio.file.Path

data class TargetPlatformConfigurationFileValidation(
    val duplicateNames: List<String> = emptyList(),
    val blankNameCount: Int = 0,
) {
    val isValid: Boolean get() = duplicateNames.isEmpty() && blankNameCount == 0
}

data class ResolvedTargetPlatformEntry(
    val index: Int,
    val targetName: String,
    val targetType: String,
    val platform: String,
    val arguments: String,
    val cookOnLaunch: Boolean,
    val incrementalCookOnLaunch: Boolean,
)

data class EntryResolutionResult(
    val entries: List<ResolvedTargetPlatformEntry>,
    val messages: List<String>,
) {
    val isValid: Boolean get() = messages.isEmpty()
}

sealed interface SelectedTargetPlatformConfigurationResult {
    data class Valid(val configuration: TargetPlatformConfiguration) : SelectedTargetPlatformConfigurationResult
    data class MissingFile(val path: Path) : SelectedTargetPlatformConfigurationResult
    data class MalformedFile(val path: Path, val message: String) : SelectedTargetPlatformConfigurationResult
    data class DuplicateNames(val names: List<String>) : SelectedTargetPlatformConfigurationResult
    data class BlankNames(val count: Int) : SelectedTargetPlatformConfigurationResult
    data object NoSelection : SelectedTargetPlatformConfigurationResult
    data class StaleSelection(val selectedName: String) : SelectedTargetPlatformConfigurationResult
    data class EmptyConfiguration(val name: String) : SelectedTargetPlatformConfigurationResult
    data class InvalidEntries(
        val configuration: TargetPlatformConfiguration,
        val messages: List<String>,
    ) : SelectedTargetPlatformConfigurationResult {
        val name: String get() = configuration.name
    }
}

fun validateConfigurationFile(file: TargetPlatformConfigurationsFile): TargetPlatformConfigurationFileValidation {
    val blankNameCount = file.configurations.count { it.name.isBlank() }
    val duplicateNames = file.configurations
        .groupingBy { it.name }
        .eachCount()
        .filter { (name, count) -> name.isNotBlank() && count > 1 }
        .keys
        .toList()

    return TargetPlatformConfigurationFileValidation(
        duplicateNames = duplicateNames,
        blankNameCount = blankNameCount,
    )
}

fun resolveConfigurationEntries(
    configuration: TargetPlatformConfiguration,
    state: UnrealHelperSettingsState,
): EntryResolutionResult {
    val discoveredTargetsByName = state.discoveredTargets
        .associateBy { it.name.trim() }
    val discoveredPlatforms = state.discoveredPlatforms
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
    val resolvedEntries = mutableListOf<ResolvedTargetPlatformEntry>()
    val messages = mutableListOf<String>()

    configuration.entries.forEachIndexed { index, entry ->
        val targetName = entry.targetName.trim()
        val platform = entry.platform.trim()
        val target = discoveredTargetsByName[targetName]
        val targetType = target?.type?.trim()
        val problems = buildList {
            if (target == null) add("build target is not discovered")
            if (target != null && targetType !in SupportedTargetTypes) {
                add("target type '$targetType' is not supported; expected Game, Client, or Server")
            }
            if (platform !in discoveredPlatforms) add("platform is not discovered")
            if (entry.incrementalCookOnLaunch && !entry.cookOnLaunch) {
                add("incremental cook requires Cook")
            }
        }

        if (problems.isEmpty()) {
            resolvedEntries += ResolvedTargetPlatformEntry(
                index = index,
                targetName = targetName,
                targetType = requireNotNull(targetType),
                platform = platform,
                arguments = entry.arguments.trim(),
                cookOnLaunch = entry.cookOnLaunch,
                incrementalCookOnLaunch = entry.incrementalCookOnLaunch,
            )
        } else {
            messages += "Entry ${index + 1} $targetName / $platform: ${problems.joinToString("; ")}"
        }
    }

    return EntryResolutionResult(
        entries = resolvedEntries,
        messages = messages,
    )
}

fun resolveSelectedTargetPlatformConfiguration(
    loadResult: TargetPlatformConfigurationLoadResult,
    selectedName: String,
    state: UnrealHelperSettingsState,
): SelectedTargetPlatformConfigurationResult =
    when (loadResult) {
        is TargetPlatformConfigurationLoadResult.Missing ->
            SelectedTargetPlatformConfigurationResult.MissingFile(loadResult.path)
        is TargetPlatformConfigurationLoadResult.Malformed ->
            SelectedTargetPlatformConfigurationResult.MalformedFile(loadResult.path, loadResult.message)
        is TargetPlatformConfigurationLoadResult.Loaded ->
            resolveLoadedConfiguration(loadResult.file, selectedName.trim(), state)
    }

private fun resolveLoadedConfiguration(
    file: TargetPlatformConfigurationsFile,
    selectedName: String,
    state: UnrealHelperSettingsState,
): SelectedTargetPlatformConfigurationResult {
    val fileValidation = validateConfigurationFile(file)
    if (fileValidation.blankNameCount > 0) {
        return SelectedTargetPlatformConfigurationResult.BlankNames(fileValidation.blankNameCount)
    }
    if (!fileValidation.isValid) {
        return SelectedTargetPlatformConfigurationResult.DuplicateNames(fileValidation.duplicateNames)
    }
    if (selectedName.isBlank()) {
        return SelectedTargetPlatformConfigurationResult.NoSelection
    }

    val configuration = file.configurations.firstOrNull { it.name == selectedName }
        ?: return SelectedTargetPlatformConfigurationResult.StaleSelection(selectedName)
    if (configuration.entries.isEmpty()) {
        return SelectedTargetPlatformConfigurationResult.EmptyConfiguration(configuration.name)
    }

    val entryResolution = resolveConfigurationEntries(configuration, state)

    return if (entryResolution.isValid) {
        SelectedTargetPlatformConfigurationResult.Valid(configuration)
    } else {
        SelectedTargetPlatformConfigurationResult.InvalidEntries(configuration, entryResolution.messages)
    }
}

private val SupportedTargetTypes: Set<String> = UnrealTargetType.entries.map { it.name }.toSet()
