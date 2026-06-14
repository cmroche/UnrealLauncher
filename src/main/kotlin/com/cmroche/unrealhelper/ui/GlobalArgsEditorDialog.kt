package com.cmroche.unrealhelper.ui

import com.cmroche.unrealhelper.args.CommandLineArguments
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class GlobalArgsEditorDialog(project: Project, initialCommandLine: String) : DialogWrapper(project) {
    private val textArea = JBTextArea(CommandLineArguments.toEditorText(initialCommandLine), 14, 84)

    init {
        title = "Global Launch Args"
        init()
    }

    fun commandLine(): String = CommandLineArguments.fromEditorText(textArea.text)

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.add(JLabel("Edit one launch option per line:"), BorderLayout.NORTH)
        panel.add(JBScrollPane(textArea), BorderLayout.CENTER)
        return panel
    }
}

