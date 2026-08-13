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
    private var form: SettingsForm? = null

    override fun getId(): String = "com.cmroche.unrealhelper.settings"

    override fun getDisplayName(): String = "UnrealHelper"

    override fun createComponent(): JComponent {
        val form = SettingsForm()
        this.form = form
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)

        content.add(projectPanel(form))
        content.add(discoveryPanel(form))
        content.add(globalArgsPanel(form))

        val panel = JPanel(BorderLayout())
        panel.add(JBScrollPane(content), BorderLayout.CENTER)
        reset()

        return panel
    }

    override fun isModified(): Boolean {
        val form = form ?: return false
        val settings = project.service<UnrealHelperSettings>()
        return form.values() != settingsFormValues(settings)
    }

    override fun apply() {
        val form = form ?: return
        val settings = project.service<UnrealHelperSettings>()
        applySettingsFormValues(settings, form.values())
    }

    override fun reset() {
        val form = form ?: return
        val settings = project.service<UnrealHelperSettings>()
        form.setValues(settingsFormValues(settings))
        form.discoverySummary.text = discoverySummary(settings.state)
    }

    override fun disposeUIResources() {
        form = null
    }

    private fun projectPanel(form: SettingsForm): JPanel {
        val panel = sectionPanel("Project")
        val fields = JPanel(GridBagLayout())
        addFormRow(fields, 0, ".uproject:", form.uprojectPath)
        addFormRow(fields, 1, "Workspace Root:", form.workspaceRoot)
        addFormRow(fields, 2, "Engine Root:", form.engineRoot)
        addFormRow(fields, 3, "Package Dir:", form.packageDirectory)
        addFormRow(fields, 4, "Build Configuration:", form.buildConfiguration)
        panel.add(fields, BorderLayout.CENTER)
        return panel
    }

    private fun discoveryPanel(form: SettingsForm): JPanel {
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
        panel.add(JBScrollPane(form.discoverySummary), BorderLayout.CENTER)
        return panel
    }

    private fun globalArgsPanel(form: SettingsForm): JPanel {
        val panel = sectionPanel("Global Run/Debug Args")
        panel.add(JBLabel("Global launch arguments, one option per line:"), BorderLayout.NORTH)
        panel.add(JBScrollPane(form.commandLine), BorderLayout.CENTER)
        panel.add(form.applyToRunDebug, BorderLayout.SOUTH)
        return panel
    }

    private fun sectionPanel(title: String): JPanel =
        JPanel(BorderLayout(0, 8)).also {
            it.border = BorderFactory.createTitledBorder(title)
        }

    private fun addFormRow(form: JPanel, row: Int, label: String, field: JComponent) {
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

    private class SettingsForm {
        val uprojectPath = JBTextField()
        val workspaceRoot = JBTextField()
        val packageDirectory = JBTextField()
        val engineRoot = JBTextField()
        val buildConfiguration = ComboBox<String>(UnrealHelperSettings.BuildConfigurations.toTypedArray())
        val discoverySummary = JBTextArea(6, 72).also { it.isEditable = false }
        val commandLine = JBTextArea(10, 72)
        val applyToRunDebug = JCheckBox("Apply global arguments to Rider Run/Debug launches")

        fun values(): SettingsFormValues = SettingsFormValues(
            uprojectPath = uprojectPath.text,
            workspaceRoot = workspaceRoot.text,
            packageDirectory = packageDirectory.text,
            engineRoot = engineRoot.text,
            buildConfiguration = selectedBuildConfiguration(),
            commandLine = commandLine.text,
            applyToRunDebug = applyToRunDebug.isSelected,
        )

        fun setValues(values: SettingsFormValues) {
            uprojectPath.text = values.uprojectPath
            workspaceRoot.text = values.workspaceRoot
            packageDirectory.text = values.packageDirectory
            engineRoot.text = values.engineRoot
            buildConfiguration.selectedItem = values.buildConfiguration
            commandLine.text = values.commandLine
            applyToRunDebug.isSelected = values.applyToRunDebug
        }

        private fun selectedBuildConfiguration(): String =
            (buildConfiguration.selectedItem as? String)
                ?.takeIf { it in UnrealHelperSettings.BuildConfigurations }
                ?: UnrealHelperSettings.DefaultBuildConfiguration
    }
}

internal data class SettingsFormValues(
    val uprojectPath: String,
    val workspaceRoot: String,
    val packageDirectory: String,
    val engineRoot: String,
    val buildConfiguration: String,
    val commandLine: String,
    val applyToRunDebug: Boolean,
)

internal fun settingsFormValues(settings: UnrealHelperSettings): SettingsFormValues {
    val state = settings.state
    return SettingsFormValues(
        uprojectPath = state.uprojectPath,
        workspaceRoot = state.workspaceRoot,
        packageDirectory = settings.effectivePackageDirectory(),
        engineRoot = state.engineRoot,
        buildConfiguration = settings.effectiveBuildConfiguration(),
        commandLine = CommandLineArguments.toEditorText(state.activeCommandLine),
        applyToRunDebug = state.applyToRunDebug,
    )
}

internal fun applySettingsFormValues(settings: UnrealHelperSettings, values: SettingsFormValues) {
    val state = settings.state
    state.uprojectPath = values.uprojectPath.trim()
    state.workspaceRoot = values.workspaceRoot.trim()
    state.packageDirectory = values.packageDirectory.trim()
    state.engineRoot = values.engineRoot.trim()
    state.buildConfiguration = values.buildConfiguration
        .takeIf { it in UnrealHelperSettings.BuildConfigurations }
        ?: UnrealHelperSettings.DefaultBuildConfiguration
    state.applyToRunDebug = values.applyToRunDebug
    settings.setActiveCommandLine(CommandLineArguments.fromEditorText(values.commandLine))
}
