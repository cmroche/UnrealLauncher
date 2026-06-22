package com.cmroche.unrealhelper.terminal

import com.cmroche.unrealhelper.command.UnrealCommand
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalView

class UnrealTerminalRunner(
    private val project: Project,
) {
    fun run(command: UnrealCommand) {
        ApplicationManager.getApplication().invokeLater {
            val terminalWidget = TerminalView.getInstance(project)
                .createLocalShellWidget(command.workingDirectory, command.title)

            terminalWidget.executeCommand(command.shellLine())
        }
    }
}
