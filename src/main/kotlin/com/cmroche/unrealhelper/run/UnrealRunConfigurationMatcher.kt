package com.cmroche.unrealhelper.run

import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import java.nio.file.Path

data class RunConfigurationMatchData(
    val configurationName: String,
    val configurationTypeId: String,
    val factoryId: String,
    val executablePath: String?,
    val workingDirectory: String?,
    val arguments: List<String>,
)

object UnrealRunConfigurationMatcher {
    fun hasInjectionSettings(state: UnrealHelperSettingsState): Boolean =
        state.activeCommandLine.isNotBlank() &&
            hasUnrealProjectContext(state)

    fun hasUnrealProjectContext(state: UnrealHelperSettingsState): Boolean =
        state.uprojectPath.isNotBlank() || state.discoveredTargets.isNotEmpty()

    fun isLikelyUnrealRunConfiguration(
        data: RunConfigurationMatchData,
        state: UnrealHelperSettingsState,
    ): Boolean {
        if (!hasUnrealProjectContext(state)) return false

        val allText = listOfNotNull(
            data.configurationName,
            data.configurationTypeId,
            data.factoryId,
            data.executablePath,
            data.workingDirectory,
            data.arguments.joinToString(" "),
        ).joinToString(" ").lowercase()

        if (allText.contains(".uproject") || allText.contains("unreal") || allText.contains("ue4") || allText.contains("ue5")) {
            return true
        }

        val launchIdentityText = listOfNotNull(
            data.configurationName,
            data.configurationTypeId,
            data.factoryId,
            data.executablePath?.fileNameText(),
            data.arguments.joinToString(" "),
        ).joinToString(" ").lowercase()
        val projectName = state.uprojectPath
            .takeIf { it.isNotBlank() }
            ?.let { Path.of(it).fileName.toString().removeSuffix(".uproject") }
        if (!projectName.isNullOrBlank() && launchIdentityText.contains(projectName.lowercase())) {
            return true
        }

        return state.discoveredTargets.any { target ->
            target.name.isNotBlank() && launchIdentityText.contains(target.name.lowercase())
        }
    }

    private fun String.fileNameText(): String =
        replace('\\', '/').substringAfterLast('/')
}
