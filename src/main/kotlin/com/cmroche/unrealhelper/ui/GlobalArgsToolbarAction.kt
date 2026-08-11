package com.cmroche.unrealhelper.ui

import com.cmroche.unrealhelper.run.RunConfigurationMatchData
import com.cmroche.unrealhelper.run.UnrealRunConfigurationMatcher
import com.cmroche.unrealhelper.discovery.UnrealProjectDiscoveryService
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicComboBoxEditor
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

class GlobalArgsToolbarAction : DumbAwareAction("UnrealHelper Args"), CustomComponentAction {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        editArgs(project, null)
    }

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isVisible = project != null
        event.presentation.isEnabled = project != null && isCompatibleWithSelectedRunConfiguration(project)
        event.presentation.putClientProperty(ProjectKey, project)

        val comboBox = event.presentation.getClientProperty(CustomComponentAction.COMPONENT_KEY) as? GlobalArgsComboBox
        if (comboBox != null) {
            SwingUtilities.invokeLater {
                comboBox.attachProject(project)
            }
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val comboBox = GlobalArgsComboBox()

        fun project(): Project? = comboBox.project ?: projectFor(comboBox)
        fun updateEnabledState(enabled: Boolean) {
            comboBox.isEnabled = enabled
            comboBox.editor.editorComponent?.isEnabled = enabled
        }

        comboBox.isEditable = true
        comboBox.isOpaque = false
        comboBox.background = toolbarBackground()
        comboBox.editor = object : BasicComboBoxEditor() {
            override fun createEditorComponent(): JTextField =
                ExtendableTextField().also { textField ->
                    textField.isOpaque = false
                    textField.background = toolbarBackground()
                    textField.border = null
                    textField.toolTipText = "UnrealHelper global launch arguments"
                    textField.document.addDocumentListener(object : DocumentListener {
                        override fun insertUpdate(event: DocumentEvent) = syncFromToolbarText()
                        override fun removeUpdate(event: DocumentEvent) = syncFromToolbarText()
                        override fun changedUpdate(event: DocumentEvent) = syncFromToolbarText()

                        private fun syncFromToolbarText() {
                            if (comboBox.refreshing) return

                            val currentProject = project() ?: return
                            syncToolbarCommandLine(
                                settings = currentProject.service<UnrealHelperSettings>(),
                                toolbarEditorCommandLine = textField.text,
                            )
                        }
                    })
                    textField.addExtension(
                        ExtendableTextComponent.Extension.create(
                            AllIcons.Actions.ArrowExpand,
                            AllIcons.Actions.ArrowExpand,
                            "Open the expanded global launch arguments editor",
                        ) {
                            val currentProject = project() ?: return@create
                            editArgs(currentProject, comboBox)
                        },
                    )
                }
        }
        comboBox.prototypeDisplayValue = "-game -windowed -resx=1080 -resy=1920 -log -newconsole"
        comboBox.preferredSize = Dimension(CommandLineInputWidth, comboBox.preferredSize.height)
        comboBox.toolTipText = "UnrealHelper global launch arguments"
        updateEnabledState(presentation.isEnabled)
        presentation.addPropertyChangeListener { event ->
            if (event.propertyName == Presentation.PROP_ENABLED) {
                SwingUtilities.invokeLater {
                    updateEnabledState(event.newValue == true)
                }
            }
        }

        comboBox.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) {
                val currentProject = project() ?: return
                comboBox.refreshSelection(currentProject.service<UnrealHelperSettings>())
            }
            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) = Unit
            override fun popupMenuCanceled(event: PopupMenuEvent) = Unit
        })

        comboBox.addActionListener {
            if (comboBox.refreshing) return@addActionListener

            val currentProject = project() ?: return@addActionListener
            val commandLine = comboBox.editor.item?.toString().orEmpty()
            currentProject.service<UnrealHelperSettings>().setActiveCommandLine(commandLine)
        }

        SwingUtilities.invokeLater {
            comboBox.attachProject(presentation.getClientProperty(ProjectKey))
        }

        return comboBox
    }

    private fun editArgs(project: Project, comboBox: ComboBox<String>?) {
        val settings = project.service<UnrealHelperSettings>()
        val initialCommandLine = syncToolbarCommandLine(settings, comboBox?.editorCommandLine())
        val dialog = GlobalArgsEditorDialog(project, initialCommandLine)

        if (dialog.showAndGet()) {
            settings.setActiveCommandLine(dialog.commandLine())
            comboBox?.editor?.item = settings.state.activeCommandLine
        }
    }

    private fun projectFor(component: JComponent): Project? {
        val dataContext = DataManager.getInstance().getDataContext(component)
        return CommonDataKeys.PROJECT.getData(dataContext)
    }

    private fun isCompatibleWithSelectedRunConfiguration(project: Project): Boolean {
        val state = compatibilityState(project) ?: return false

        val selectedSettings = RunManager.getInstance(project).selectedConfiguration ?: return true
        return UnrealRunConfigurationMatcher.isLikelyUnrealRunConfiguration(
            selectedSettings.toMatchData(),
            state,
        )
    }

    private fun RunnerAndConfigurationSettings.toMatchData(): RunConfigurationMatchData =
        RunConfigurationMatchData(
            configurationName = name,
            configurationTypeId = type.id,
            factoryId = factory.id,
            executablePath = null,
            workingDirectory = null,
            arguments = emptyList(),
        )

    private fun ComboBox<String>.editorCommandLine(): String {
        val editorComponent = editor.editorComponent
        return if (editorComponent is JTextField) {
            editorComponent.text
        } else {
            editor.item?.toString().orEmpty()
        }
    }

    private companion object {
        private const val CommandLineInputWidth = 330
        private val ProjectKey = Key.create<Project>("UnrealHelper.globalArgsProject")

        fun toolbarBackground() = JBColor.namedColor("MainToolbar.background", UIUtil.getPanelBackground())

        fun compatibilityState(project: Project): UnrealHelperSettingsState? {
            val state = project.service<UnrealHelperSettings>().state
            if (UnrealRunConfigurationMatcher.hasUnrealProjectContext(state)) {
                return state
            }

            project.service<UnrealProjectDiscoveryService>().refresh()
            return null
        }
    }
}

internal class GlobalArgsComboBox : ComboBox<String>() {
    var project: Project? = null
        private set
    var refreshing: Boolean = false
        private set
    private var initialSelectionRestored: Boolean = false

    fun attachProject(project: Project?) {
        if (this.project !== project) {
            this.project = project
            initialSelectionRestored = false
        }
        if (project != null && !project.isDisposed) {
            restoreInitialSelection(project.service<UnrealHelperSettings>())
        }
    }

    internal fun restoreInitialSelection(settings: UnrealHelperSettings) {
        if (initialSelectionRestored) return

        refreshSelection(settings)
        initialSelectionRestored = true
    }

    fun refreshSelection(settings: UnrealHelperSettings) {
        refreshing = true
        try {
            restoreCommandLineSelection(this, settings)
        } finally {
            refreshing = false
        }
    }
}

internal fun syncToolbarCommandLine(
    settings: UnrealHelperSettings,
    toolbarEditorCommandLine: String?,
): String {
    if (toolbarEditorCommandLine != null) {
        settings.setActiveCommandLine(toolbarEditorCommandLine, rememberRecent = false)
    }
    return settings.state.activeCommandLine
}

internal fun restoreCommandLineSelection(
    comboBox: JComboBox<String>,
    settings: UnrealHelperSettings,
) {
    val state = settings.state
    val values = listOf(state.activeCommandLine)
        .filter { it.isNotBlank() } + settings.knownCommandLines()

    comboBox.model = DefaultComboBoxModel(values.distinct().toTypedArray())
    comboBox.selectedItem = state.activeCommandLine
    comboBox.editor.item = state.activeCommandLine
}
