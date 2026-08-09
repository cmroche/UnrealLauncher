package com.cmroche.unrealhelper.actions

import com.cmroche.unrealhelper.workflow.UnrealIdeConfigurationAndPlatform
import com.intellij.openapi.project.Project
import com.jetbrains.rider.projectView.SolutionConfigurationManager

internal object RiderUnrealPlatformCompatibility {
    fun availableConfigurations(project: Project): List<UnrealIdeConfigurationAndPlatform>? =
        runCatching {
            SolutionConfigurationManager.tryGetInstance(project)
                ?.solutionConfigurationsAndPlatforms
                ?.takeIf { it.isNotEmpty() }
                ?.map {
                    UnrealIdeConfigurationAndPlatform(
                        configuration = it.configuration,
                        platform = it.platform,
                    )
                }
        }.getOrNull()
}
