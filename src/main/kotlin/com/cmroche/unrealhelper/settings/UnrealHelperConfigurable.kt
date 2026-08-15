package com.cmroche.unrealhelper.settings

import com.cmroche.unrealhelper.discovery.UnrealProjectDiscoveryService
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
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
import javax.swing.JComponent
import javax.swing.JPanel

class UnrealHelperConfigurable(private val project: Project) : SearchableConfigurable {
    private var form: SettingsForm? = null

    override fun getId(): String = "com.cmroche.unrealhelper.settings"

    override fun getDisplayName(): String = "Unreal Launcher"

    override fun createComponent(): JComponent {
        val form = SettingsForm()
        this.form = form
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)

        content.add(projectPanel(form))
        content.add(discoveryPanel(form))

        val panel = JPanel(BorderLayout())
        panel.add(JBScrollPane(content), BorderLayout.CENTER)
        reset()

        return panel
    }

    override fun isModified(): Boolean = false

    override fun apply() = Unit

    override fun reset() {
        val form = form ?: return
        val settings = project.service<UnrealHelperSettings>()
        form.setValues(settingsDisplayValues(settings))
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
        val uprojectPath = readOnlyTextField()
        val workspaceRoot = readOnlyTextField()
        val packageDirectory = readOnlyTextField()
        val engineRoot = readOnlyTextField()
        val buildConfiguration = readOnlyTextField()
        val discoverySummary = JBTextArea(6, 72).also { it.isEditable = false }

        fun setValues(values: SettingsDisplayValues) {
            uprojectPath.text = values.uprojectPath
            workspaceRoot.text = values.workspaceRoot
            packageDirectory.text = values.packageDirectory
            engineRoot.text = values.engineRoot
            buildConfiguration.text = values.buildConfiguration
        }

        private fun readOnlyTextField(): JBTextField =
            JBTextField().also { it.isEditable = false }
    }
}

internal data class SettingsDisplayValues(
    val uprojectPath: String,
    val workspaceRoot: String,
    val packageDirectory: String,
    val engineRoot: String,
    val buildConfiguration: String,
)

internal fun settingsDisplayValues(settings: UnrealHelperSettings): SettingsDisplayValues {
    val state = settings.state
    return SettingsDisplayValues(
        uprojectPath = state.uprojectPath,
        workspaceRoot = state.workspaceRoot,
        packageDirectory = settings.effectivePackageDirectory(),
        engineRoot = state.engineRoot,
        buildConfiguration = settings.effectiveBuildConfiguration(),
    )
}
