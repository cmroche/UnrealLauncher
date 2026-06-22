package com.cmroche.unrealhelper.terminal

import com.cmroche.unrealhelper.command.UnrealCommand
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager

class UnrealTerminalRunner(
    private val project: Project,
) {
    fun run(command: UnrealCommand) {
        ApplicationManager.getApplication().invokeLater(
            {
                if (!project.isDisposed) {
                    val terminalView = TerminalToolWindowTabsManager.getInstance(project)
                        .createTabBuilder()
                        .workingDirectory(command.workingDirectory)
                        .tabName(command.title)
                        .requestFocus(true)
                        .createTab()
                        .view

                    terminalView.createSendTextBuilder()
                        .shouldExecute()
                        .send(command.shellLine())
                }
            },
            project.disposed,
        )
    }
}
