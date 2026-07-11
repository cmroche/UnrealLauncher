package com.cmroche.unrealhelper.launch

import com.cmroche.unrealhelper.workflow.Launch
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.util.execution.ParametersListUtil

internal object UnrealLaunchCommandBuilder {
    fun build(action: Launch, artifact: ResolvedLaunchArtifact): GeneralCommandLine {
        val command = GeneralCommandLine(artifact.executable.toString())
            .withWorkingDirectory(artifact.workingDirectory)
        if (artifact.executable.startsWith(artifact.engineRoot) && artifact.projectPath != null) {
            command.addParameter(artifact.projectPath.toString())
        }
        command.parametersList.addAll(parse(action.entryArguments) + parse(action.globalArguments))
        return command
    }

    private fun parse(arguments: String): List<String> =
        ParametersListUtil.parse(arguments).filter { it.isNotEmpty() }
}
