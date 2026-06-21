package com.cmroche.unrealhelper.ui

import com.cmroche.unrealhelper.run.RunConfigurationMatchData
import com.cmroche.unrealhelper.run.UnrealRunConfigurationMatcher
import com.cmroche.unrealhelper.discovery.UnrealProjectDiscovery
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.cmroche.unrealhelper.settings.UnrealTargetState
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
import com.intellij.openapi.util.Key
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.nio.file.Paths
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicComboBoxEditor
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
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val comboBox = ComboBox<String>()
        var refreshing = false

        fun project(): Project? = projectFor(comboBox)
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

        fun refresh() {
            val currentProject = project() ?: return
            val settings = currentProject.service<UnrealHelperSettings>()
            val state = settings.state
            val values = listOf(state.activeCommandLine)
                .filter { it.isNotBlank() } + settings.knownCommandLines()

            refreshing = true
            comboBox.model = DefaultComboBoxModel(values.distinct().toTypedArray())
            comboBox.selectedItem = state.activeCommandLine
            comboBox.editor.item = state.activeCommandLine
            refreshing = false
        }

        comboBox.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = refresh()
            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) = Unit
            override fun popupMenuCanceled(event: PopupMenuEvent) = Unit
        })

        comboBox.addActionListener {
            if (refreshing) return@addActionListener

            val currentProject = project() ?: return@addActionListener
            val commandLine = comboBox.editor.item?.toString().orEmpty()
            currentProject.service<UnrealHelperSettings>().setActiveCommandLine(commandLine)
        }

        return comboBox
    }

    private fun editArgs(project: Project, comboBox: ComboBox<String>?) {
        val settings = project.service<UnrealHelperSettings>()
        val dialog = GlobalArgsEditorDialog(project, settings.state.activeCommandLine)

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

    private companion object {
        private const val CommandLineInputWidth = 330
        private val CompatibilityStateKey = Key.create<UnrealHelperSettingsState>("UnrealHelper.compatibilityState")

        fun toolbarBackground() = JBColor.namedColor("MainToolbar.background", UIUtil.getPanelBackground())

        fun compatibilityState(project: Project): UnrealHelperSettingsState? {
            val state = project.service<UnrealHelperSettings>().state
            if (UnrealRunConfigurationMatcher.hasUnrealProjectContext(state)) {
                return state
            }

            val cachedState = project.getUserData(CompatibilityStateKey)
            if (cachedState != null) {
                return cachedState.takeIf(UnrealRunConfigurationMatcher::hasUnrealProjectContext)
            }

            val discoveredState = discoverCompatibilityState(project)
            project.putUserData(CompatibilityStateKey, discoveredState)
            return discoveredState.takeIf(UnrealRunConfigurationMatcher::hasUnrealProjectContext)
        }

        private fun discoverCompatibilityState(project: Project): UnrealHelperSettingsState {
            val basePath = project.basePath ?: return UnrealHelperSettingsState()
            val result = UnrealProjectDiscovery.discover(Paths.get(basePath))
            return UnrealHelperSettingsState().also { state ->
                state.workspaceRoot = result.workspaceRoot.orEmpty()
                state.uprojectPath = result.uprojectPath.orEmpty()
                state.discoveredTargets = result.targets.map { target ->
                    UnrealTargetState().also {
                        it.name = target.name
                        it.type = target.type.name
                        it.path = target.path
                    }
                }.toMutableList()
            }
        }
    }
}
