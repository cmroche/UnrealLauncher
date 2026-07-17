package com.cmroche.unrealhelper.run

import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.intellij.execution.configurations.ParametersList
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import com.jetbrains.rider.cpp.run.configurations.CppConfigurationParametersExtension
import com.jetbrains.rider.run.configurations.exe.ExeConfigurationParameters

class UnrealHelperCppConfigurationParametersExtension(
    private val project: Project,
) : CppConfigurationParametersExtension {
    override fun process(parameters: ExeConfigurationParameters) {
        injectGlobalArgsIntoRiderUproject(
            parameters = parameters,
            state = project.service<UnrealHelperSettings>().state,
        )
    }
}

internal fun injectGlobalArgsIntoRiderUproject(
    parameters: ExeConfigurationParameters,
    state: UnrealHelperSettingsState,
): Boolean {
    if (!UnrealRunConfigurationMatcher.hasInjectionSettings(state)) return false

    val existingArguments = ParametersList.parse(parameters.programParameters).toList()
    if (existingArguments.none { it.trim('"').endsWith(".uproject", ignoreCase = true) }) return false

    val argumentsToInject = GlobalArgsCommandLineInjector.argumentsToInject(
        existingArguments = existingArguments,
        globalCommandLine = state.activeCommandLine,
    )
    if (argumentsToInject.isEmpty()) return false

    parameters.programParameters = listOf(
        parameters.programParameters.trim(),
        ParametersListUtil.join(argumentsToInject),
    ).filter { it.isNotEmpty() }.joinToString(" ")
    return true
}
