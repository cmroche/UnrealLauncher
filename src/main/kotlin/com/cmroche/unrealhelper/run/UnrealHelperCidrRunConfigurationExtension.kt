package com.cmroche.unrealhelper.run

import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.components.service
import com.jetbrains.cidr.execution.CidrRunConfigurationExtensionBase
import com.jetbrains.cidr.execution.ConfigurationExtensionContext
import com.jetbrains.cidr.lang.toolchains.CidrToolEnvironment
import com.jetbrains.cidr.lang.workspace.OCRunConfiguration

class UnrealHelperCidrRunConfigurationExtension : CidrRunConfigurationExtensionBase() {
    override fun isApplicableFor(configuration: OCRunConfiguration<*, *>): Boolean =
        UnrealRunConfigurationMatcher.hasInjectionSettings(configuration.project.service<UnrealHelperSettings>().state)

    override fun isEnabledFor(
        configuration: OCRunConfiguration<*, *>,
        toolEnvironment: CidrToolEnvironment,
        runnerSettings: RunnerSettings?,
    ): Boolean = isApplicableFor(configuration)

    @Throws(ExecutionException::class)
    override fun patchCommandLine(
        configuration: OCRunConfiguration<*, *>,
        runnerSettings: RunnerSettings?,
        toolEnvironment: CidrToolEnvironment,
        commandLine: GeneralCommandLine,
        executorId: String,
        context: ConfigurationExtensionContext,
    ) {
        val settings = configuration.project.service<UnrealHelperSettings>()
        val state = settings.state
        val factory = configuration.factory
        val matchData = RunConfigurationMatchData(
            configurationName = configuration.name,
            configurationTypeId = factory?.type?.id.orEmpty(),
            factoryId = factory?.id.orEmpty(),
            executablePath = commandLine.exePath,
            workingDirectory = commandLine.workingDirectory?.toString(),
            arguments = commandLine.parametersList.list,
        )

        if (UnrealRunConfigurationMatcher.isLikelyUnrealRunConfiguration(matchData, state)) {
            GlobalArgsCommandLineInjector.inject(commandLine, state.activeCommandLine)
        }
    }
}
