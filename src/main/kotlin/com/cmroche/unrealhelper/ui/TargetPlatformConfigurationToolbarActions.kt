package com.cmroche.unrealhelper.ui

import com.cmroche.unrealhelper.config.TargetPlatformConfigurationLoadResult
import com.cmroche.unrealhelper.config.TargetPlatformConfigurationService
import com.cmroche.unrealhelper.config.TargetPlatformConfigurationsFile
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.util.ui.UIUtil
import java.awt.Font
import java.beans.PropertyChangeEvent
import javax.swing.JComponent

class TargetPlatformConfigurationSelectorAction : ComboBoxAction(), DumbAware {
    init {
        val initialState = TargetPlatformConfigurationSelectorState.Unavailable
        templatePresentation.putClientProperty(SelectorStateKey, initialState)
        templatePresentation.text = initialState.text
        templatePresentation.description = initialState.description
    }

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isVisible = project != null

        if (project == null) {
            applySelectorState(event.presentation, TargetPlatformConfigurationSelectorState.Unavailable)
            return
        }

        val service = project.service<TargetPlatformConfigurationService>()
        applySelectorState(
            event.presentation,
            targetPlatformConfigurationSelectorState(
                loadResult = service.load(),
                selectedName = project.service<UnrealHelperSettings>().state.selectedTargetPlatformConfigurationName,
            ),
        )
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun createComboBoxButton(presentation: Presentation): ComboBoxButton =
        object : ComboBoxButton(presentation) {
            private val regularFont = font
            private val regularForeground = foreground

            init {
                updateSetupStyle()
            }

            override fun presentationChanged(event: PropertyChangeEvent) {
                super.presentationChanged(event)
                updateSetupStyle()
            }

            override fun showPopup() {
                val project = CommonDataKeys.PROJECT.getData(dataContext)
                val state = project?.let {
                    targetPlatformConfigurationSelectorState(
                        loadResult = it.service<TargetPlatformConfigurationService>().load(),
                        selectedName = it.service<UnrealHelperSettings>().state.selectedTargetPlatformConfigurationName,
                    )
                }
                if (project != null && state?.opensManagement == true) {
                    showTargetPlatformConfigurationDialog(project)
                    return
                }

                super.showPopup()
            }

            private fun updateSetupStyle() {
                val usesSetupStyle = presentation.getClientProperty(SelectorStateKey)
                    ?.usesSetupStyle
                    ?: true
                font = if (usesSetupStyle) {
                    regularFont.deriveFont(regularFont.style or Font.ITALIC)
                } else {
                    regularFont
                }
                foreground = if (usesSetupStyle) UIUtil.getContextHelpForeground() else regularForeground
            }
        }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return DefaultActionGroup()
        val service = project.service<TargetPlatformConfigurationService>()
        val loadResult = service.load()

        return DefaultActionGroup().also { group ->
            if (loadResult is TargetPlatformConfigurationLoadResult.Loaded) {
                loadResult.file.configurations.forEach { configuration ->
                    group.add(TargetPlatformConfigurationToggleAction(project, configuration.name))
                }
                group.addSeparator()
            }
            group.add(TargetPlatformConfigurationManageAction())
        }
    }

    private class TargetPlatformConfigurationToggleAction(
        private val project: Project,
        private val configurationName: String,
    ) : ToggleAction(configurationName), DumbAware {
        override fun isSelected(event: AnActionEvent): Boolean {
            val selectedName = project.service<UnrealHelperSettings>().state.selectedTargetPlatformConfigurationName
            return selectedName == configurationName
        }

        override fun setSelected(event: AnActionEvent, selected: Boolean) {
            val state = project.service<UnrealHelperSettings>().state
            state.selectedTargetPlatformConfigurationName = if (selected) configurationName else ""
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

}

class TargetPlatformConfigurationManageAction : DumbAwareAction(
    ConfigureText,
    "Create, modify, and delete shared Target & Platform configurations",
    AllIcons.General.Settings,
) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        showTargetPlatformConfigurationDialog(project)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isVisible = event.project != null
        event.presentation.isEnabled = event.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

private fun showTargetPlatformConfigurationDialog(project: Project) {
    val service = project.service<TargetPlatformConfigurationService>()
    val loadResult = service.loadForManagement()
    val loadError = targetPlatformConfigurationManagementError(loadResult)
    if (loadError != null) {
        Messages.showErrorDialog(project, loadError, "UnrealHelper")
        return
    }

    val initialFile = when (loadResult) {
        is TargetPlatformConfigurationLoadResult.Loaded -> loadResult.file
        is TargetPlatformConfigurationLoadResult.Missing -> TargetPlatformConfigurationsFile()
        is TargetPlatformConfigurationLoadResult.Malformed -> return
    }
    val selectedName = project.service<UnrealHelperSettings>().state.selectedTargetPlatformConfigurationName
    val dialog = TargetPlatformConfigurationDialog(project, initialFile, selectedName)

    if (dialog.showAndGet()) {
        try {
            service.saveManagedConfigurations(dialog.configurations(), dialog.selectedConfigurationName())
        } catch (exception: Exception) {
            Messages.showErrorDialog(
                project,
                "Could not save Target & Platform configurations: " +
                    (exception.message ?: exception.javaClass.simpleName),
                "UnrealHelper",
            )
        }
    }
}

internal sealed interface TargetPlatformConfigurationSelectorState {
    val text: String
    val description: String
    val isEnabled: Boolean
    val usesSetupStyle: Boolean
    val opensManagement: Boolean

    data object Setup : TargetPlatformConfigurationSelectorState {
        override val text: String = ConfigureText
        override val description: String = "Configure Target & Platform configurations"
        override val isEnabled: Boolean = true
        override val usesSetupStyle: Boolean = true
        override val opensManagement: Boolean = true
    }

    data object Unselected : TargetPlatformConfigurationSelectorState {
        override val text: String = ConfigureText
        override val description: String = DefaultDescription
        override val isEnabled: Boolean = true
        override val usesSetupStyle: Boolean = true
        override val opensManagement: Boolean = false
    }

    data class Selected(val name: String) : TargetPlatformConfigurationSelectorState {
        override val text: String = name
        override val description: String = "$SelectedDescriptionPrefix$name"
        override val isEnabled: Boolean = true
        override val usesSetupStyle: Boolean = false
        override val opensManagement: Boolean = false
    }

    data object Unavailable : TargetPlatformConfigurationSelectorState {
        override val text: String = ConfigureText
        override val description: String = DefaultDescription
        override val isEnabled: Boolean = false
        override val usesSetupStyle: Boolean = true
        override val opensManagement: Boolean = false
    }
}

internal fun targetPlatformConfigurationSelectorState(
    loadResult: TargetPlatformConfigurationLoadResult,
    selectedName: String,
): TargetPlatformConfigurationSelectorState {
    if (targetPlatformConfigurationNeedsSetup(loadResult)) {
        return TargetPlatformConfigurationSelectorState.Setup
    }

    if (loadResult !is TargetPlatformConfigurationLoadResult.Loaded) {
        return TargetPlatformConfigurationSelectorState.Unavailable
    }
    val trimmedName = selectedName.trim()
    if (trimmedName.isEmpty()) {
        return TargetPlatformConfigurationSelectorState.Unselected
    }

    return trimmedName.takeIf { name ->
        loadResult.file.configurations.any { it.name == name }
    }?.let { TargetPlatformConfigurationSelectorState.Selected(it) }
        ?: TargetPlatformConfigurationSelectorState.Unselected
}

internal fun targetPlatformConfigurationNeedsSetup(
    loadResult: TargetPlatformConfigurationLoadResult,
): Boolean =
    loadResult is TargetPlatformConfigurationLoadResult.Missing ||
        loadResult is TargetPlatformConfigurationLoadResult.Loaded &&
        loadResult.file.configurations.isEmpty()

internal fun targetPlatformConfigurationManagementError(
    loadResult: TargetPlatformConfigurationLoadResult,
): String? =
    when (loadResult) {
        is TargetPlatformConfigurationLoadResult.Malformed ->
            "Could not open Target & Platform configurations from ${loadResult.path}: ${loadResult.message}"
        is TargetPlatformConfigurationLoadResult.Loaded,
        is TargetPlatformConfigurationLoadResult.Missing,
        -> null
    }

private const val ConfigureText = "Configure ..."
private const val DefaultDescription = "Select Target & Platform configuration"
private const val SelectedDescriptionPrefix = "Selected Target & Platform configuration: "
private val SelectorStateKey = Key.create<TargetPlatformConfigurationSelectorState>(
    "UnrealHelper.targetPlatformConfigurationSelectorState",
)

private fun applySelectorState(
    presentation: Presentation,
    state: TargetPlatformConfigurationSelectorState,
) {
    presentation.putClientProperty(SelectorStateKey, state)
    presentation.text = state.text
    presentation.description = state.description
    presentation.isEnabled = state.isEnabled
}
