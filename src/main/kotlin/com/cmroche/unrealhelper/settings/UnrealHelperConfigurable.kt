package com.cmroche.unrealhelper.settings

import com.cmroche.unrealhelper.args.CommandLineArguments
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class UnrealHelperConfigurable(private val project: Project) : SearchableConfigurable {
    private var rootPanel: JPanel? = null
    private var commandLineArea: JBTextArea? = null
    private var applyToRunDebug: JCheckBox? = null

    override fun getId(): String = "com.cmroche.unrealhelper.settings"

    override fun getDisplayName(): String = "UnrealHelper"

    override fun createComponent(): JComponent {
        val area = JBTextArea(10, 72)
        val checkBox = JCheckBox("Apply global arguments to Rider Run/Debug launches")
        val panel = JPanel(BorderLayout(0, 8))

        panel.add(JLabel("Global launch arguments, one option per line:"), BorderLayout.NORTH)
        panel.add(JBScrollPane(area), BorderLayout.CENTER)
        panel.add(checkBox, BorderLayout.SOUTH)

        commandLineArea = area
        applyToRunDebug = checkBox
        rootPanel = panel

        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val state = project.service<UnrealHelperSettings>().state
        return commandLineArea?.text != CommandLineArguments.toEditorText(state.activeCommandLine) ||
            applyToRunDebug?.isSelected != state.applyToRunDebug
    }

    override fun apply() {
        val settings = project.service<UnrealHelperSettings>()
        val state = settings.state

        state.applyToRunDebug = applyToRunDebug?.isSelected ?: true
        settings.setActiveCommandLine(CommandLineArguments.fromEditorText(commandLineArea?.text.orEmpty()))
    }

    override fun reset() {
        val state = project.service<UnrealHelperSettings>().state
        commandLineArea?.text = CommandLineArguments.toEditorText(state.activeCommandLine)
        applyToRunDebug?.isSelected = state.applyToRunDebug
    }

    override fun disposeUIResources() {
        rootPanel = null
        commandLineArea = null
        applyToRunDebug = null
    }
}

