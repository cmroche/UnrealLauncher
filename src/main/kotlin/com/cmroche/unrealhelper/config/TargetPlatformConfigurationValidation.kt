package com.cmroche.unrealhelper.config

import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import java.nio.file.Path

data class TargetPlatformConfigurationFileValidation(
    val duplicateNames: List<String> = emptyList(),
) {
    val isValid: Boolean get() = duplicateNames.isEmpty()
}

sealed interface SelectedTargetPlatformConfigurationResult {
    data class Valid(val configuration: TargetPlatformConfiguration) : SelectedTargetPlatformConfigurationResult
    data class MissingFile(val path: Path) : SelectedTargetPlatformConfigurationResult
    data class MalformedFile(val path: Path, val message: String) : SelectedTargetPlatformConfigurationResult
    data class DuplicateNames(val names: List<String>) : SelectedTargetPlatformConfigurationResult
    data object NoSelection : SelectedTargetPlatformConfigurationResult
    data class StaleSelection(val selectedName: String) : SelectedTargetPlatformConfigurationResult
    data class EmptyConfiguration(val name: String) : SelectedTargetPlatformConfigurationResult
    data class InvalidEntries(val name: String, val messages: List<String>) : SelectedTargetPlatformConfigurationResult
}

fun validateConfigurationFile(file: TargetPlatformConfigurationsFile): TargetPlatformConfigurationFileValidation {
    val duplicateNames = file.configurations
        .groupingBy { it.name }
        .eachCount()
        .filter { (name, count) -> name.isNotBlank() && count > 1 }
        .keys
        .toList()

    return TargetPlatformConfigurationFileValidation(duplicateNames = duplicateNames)
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

    val discoveredTargetTypes = state.discoveredTargets.map { it.type.trim() }.filter { it.isNotEmpty() }.toSet()
    val discoveredPlatforms = state.discoveredPlatforms.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val invalidMessages = configuration.entries.mapIndexedNotNull { index, entry ->
        val problems = buildList {
            if (entry.targetType !in discoveredTargetTypes) add("target type is not discovered")
            if (entry.platform !in discoveredPlatforms) add("platform is not discovered")
        }
        if (problems.isEmpty()) {
            null
        } else {
            "Entry ${index + 1} ${entry.targetType} / ${entry.platform}: ${problems.joinToString("; ")}"
        }
    }

    return if (invalidMessages.isEmpty()) {
        SelectedTargetPlatformConfigurationResult.Valid(configuration)
    } else {
        SelectedTargetPlatformConfigurationResult.InvalidEntries(configuration.name, invalidMessages)
    }
}
