package com.cmroche.unrealhelper.settings

import com.cmroche.unrealhelper.args.CommandLineArguments
import com.cmroche.unrealhelper.discovery.UnrealProjectDiscoveryService
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

class UnrealHelperConfigurable(private val project: Project) : SearchableConfigurable {
    private var rootPanel: JPanel? = null
    private var uprojectPathField: JBTextField? = null
    private var workspaceRootField: JBTextField? = null
    private var packageDirectoryField: JBTextField? = null
    private var engineRootField: JBTextField? = null
    private var buildConfigurationComboBox: ComboBox<String>? = null
    private var discoverySummaryArea: JBTextArea? = null
    private var commandLineArea: JBTextArea? = null
    private var applyToRunDebug: JCheckBox? = null

    override fun getId(): String = "com.cmroche.unrealhelper.settings"

    override fun getDisplayName(): String = "UnrealHelper"

    override fun createComponent(): JComponent {
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)

        uprojectPathField = JBTextField()
        workspaceRootField = JBTextField()
        packageDirectoryField = JBTextField()
        engineRootField = JBTextField()
        buildConfigurationComboBox = ComboBox<String>(UnrealHelperSettings.BuildConfigurations.toTypedArray())
        discoverySummaryArea = JBTextArea(6, 72).also {
            it.isEditable = false
        }
        commandLineArea = JBTextArea(10, 72)
        applyToRunDebug = JCheckBox("Apply global arguments to Rider Run/Debug launches")

        content.add(projectPanel())
        content.add(discoveryPanel())
        content.add(globalArgsPanel())

        val panel = JPanel(BorderLayout())
        panel.add(JBScrollPane(content), BorderLayout.CENTER)
        rootPanel = panel
        reset()

        return panel
    }

    override fun isModified(): Boolean {
        val settings = project.service<UnrealHelperSettings>()
        val state = settings.state
        return uprojectPathField?.text != state.uprojectPath ||
            workspaceRootField?.text != state.workspaceRoot ||
            packageDirectoryField?.text != settings.effectivePackageDirectory() ||
            engineRootField?.text != state.engineRoot ||
            selectedBuildConfiguration() != settings.effectiveBuildConfiguration() ||
            commandLineArea?.text != CommandLineArguments.toEditorText(state.activeCommandLine) ||
            applyToRunDebug?.isSelected != state.applyToRunDebug
    }

    override fun apply() {
        val settings = project.service<UnrealHelperSettings>()
        val state = settings.state

        state.uprojectPath = uprojectPathField?.text.orEmpty().trim()
        state.workspaceRoot = workspaceRootField?.text.orEmpty().trim()
        state.packageDirectory = packageDirectoryField?.text.orEmpty().trim()
        state.engineRoot = engineRootField?.text.orEmpty().trim()
        state.buildConfiguration = selectedBuildConfiguration()
        state.applyToRunDebug = applyToRunDebug?.isSelected ?: true
        settings.setActiveCommandLine(CommandLineArguments.fromEditorText(commandLineArea?.text.orEmpty()))
    }

    override fun reset() {
        val settings = project.service<UnrealHelperSettings>()
        val state = settings.state
        uprojectPathField?.text = state.uprojectPath
        workspaceRootField?.text = state.workspaceRoot
        packageDirectoryField?.text = settings.effectivePackageDirectory()
        engineRootField?.text = state.engineRoot
        buildConfigurationComboBox?.selectedItem = settings.effectiveBuildConfiguration()
        discoverySummaryArea?.text = discoverySummary(state)
        commandLineArea?.text = CommandLineArguments.toEditorText(state.activeCommandLine)
        applyToRunDebug?.isSelected = state.applyToRunDebug
    }

    override fun disposeUIResources() {
        rootPanel = null
        uprojectPathField = null
        workspaceRootField = null
        packageDirectoryField = null
        engineRootField = null
        buildConfigurationComboBox = null
        discoverySummaryArea = null
        commandLineArea = null
        applyToRunDebug = null
    }

    private fun projectPanel(): JPanel {
        val panel = sectionPanel("Project")
        val form = JPanel(GridBagLayout())
        addFormRow(form, 0, ".uproject:", uprojectPathField)
        addFormRow(form, 1, "Workspace Root:", workspaceRootField)
        addFormRow(form, 2, "Engine Root:", engineRootField)
        addFormRow(form, 3, "Package Dir:", packageDirectoryField)
        addFormRow(form, 4, "Build Configuration:", buildConfigurationComboBox)
        panel.add(form, BorderLayout.CENTER)
        return panel
    }

    private fun discoveryPanel(): JPanel {
        val panel = sectionPanel("Detection")
        val refreshButton = JButton("Refresh from Rider Model")
        refreshButton.addActionListener {
            refreshButton.isEnabled = false
            project.service<UnrealProjectDiscoveryService>().refresh {
                refreshButton.isEnabled = true
                reset()
            }
        }

        val top = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        top.add(refreshButton)
        panel.add(top, BorderLayout.NORTH)
        panel.add(JBScrollPane(discoverySummaryArea), BorderLayout.CENTER)
        return panel
    }

    private fun globalArgsPanel(): JPanel {
        val panel = sectionPanel("Global Run/Debug Args")
        panel.add(JBLabel("Global launch arguments, one option per line:"), BorderLayout.NORTH)
        panel.add(JBScrollPane(commandLineArea), BorderLayout.CENTER)
        panel.add(applyToRunDebug, BorderLayout.SOUTH)
        return panel
    }

    private fun sectionPanel(title: String): JPanel =
        JPanel(BorderLayout(0, 8)).also {
            it.border = BorderFactory.createTitledBorder(title)
        }

    private fun addFormRow(form: JPanel, row: Int, label: String, field: JComponent?) {
        val labelConstraints = GridBagConstraints().also {
            it.gridx = 0
            it.gridy = row
            it.anchor = GridBagConstraints.WEST
            it.insets = Insets(0, 0, 8, 8)
        }
        form.add(JBLabel(label), labelConstraints)

        val fieldConstraints = GridBagConstraints().also {
            it.gridx = 1
            it.gridy = row
            it.weightx = 1.0
            it.fill = GridBagConstraints.HORIZONTAL
            it.insets = Insets(0, 0, 8, 0)
        }
        form.add(field, fieldConstraints)
    }

    private fun selectedBuildConfiguration(): String =
        (buildConfigurationComboBox?.selectedItem as? String)
            ?.takeIf { it in UnrealHelperSettings.BuildConfigurations }
            ?: UnrealHelperSettings.DefaultBuildConfiguration

    private fun discoverySummary(state: UnrealHelperSettingsState): String {
        val lines = mutableListOf<String>()
        if (state.discoveredTargets.isEmpty()) {
            lines += "Targets: none detected"
        } else {
            lines += "Targets:"
            lines += state.discoveredTargets.map { "- ${it.name} (${it.type})" }
        }

        lines += "Platforms: ${state.discoveredPlatforms.joinToString(", ").ifBlank { "none detected" }}"

        if (state.discoveryWarnings.isNotEmpty()) {
            lines += ""
            lines += "Warnings:"
            lines += state.discoveryWarnings.map { "- $it" }
        }

        return lines.joinToString("\n")
    }
}
