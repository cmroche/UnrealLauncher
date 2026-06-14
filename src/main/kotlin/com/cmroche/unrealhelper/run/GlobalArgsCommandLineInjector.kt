package com.cmroche.unrealhelper.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.ParametersList

object GlobalArgsCommandLineInjector {
    fun argumentsToInject(existingArguments: List<String>, globalCommandLine: String): List<String> {
        val existing = existingArguments.toSet()
        return ParametersList.parse(globalCommandLine)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it in existing }
    }

    fun inject(commandLine: GeneralCommandLine, globalCommandLine: String): Boolean {
        val arguments = argumentsToInject(commandLine.parametersList.list, globalCommandLine)
        if (arguments.isEmpty()) return false

        commandLine.parametersList.addAll(arguments)
        return true
    }
}
