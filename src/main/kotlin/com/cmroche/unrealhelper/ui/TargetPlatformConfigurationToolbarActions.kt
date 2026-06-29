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
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import javax.swing.JComponent

class TargetPlatformConfigurationSelectorAction : ComboBoxAction(), DumbAware {
    init {
        templatePresentation.text = DefaultText
        templatePresentation.description = DefaultDescription
    }

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isVisible = project != null

        if (project == null) {
            event.presentation.isEnabled = false
            event.presentation.text = DefaultText
            event.presentation.description = DefaultDescription
            return
        }

        val service = project.service<TargetPlatformConfigurationService>()
        service.clearStaleSelectionIfNeeded()

        val selectedName = project.service<UnrealHelperSettings>().state.selectedTargetPlatformConfigurationName
        val toolbarPresentation = targetPlatformConfigurationPresentation(selectedName)
        event.presentation.text = toolbarPresentation.text
        event.presentation.description = toolbarPresentation.description ?: DefaultDescription
        event.presentation.isEnabled = service.load() is TargetPlatformConfigurationLoadResult.Loaded
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

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

    private companion object {
        const val DefaultText = "Target & Platform"
        const val DefaultDescription = "Select Target & Platform configuration"
    }
}

class TargetPlatformConfigurationManageAction : DumbAwareAction(
    "Manage Target & Platform Configurations",
    "Create, modify, and delete shared Target & Platform configurations",
    AllIcons.General.Settings,
) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val service = project.service<TargetPlatformConfigurationService>()
        val initialFile = when (val loadResult = service.load()) {
            is TargetPlatformConfigurationLoadResult.Loaded -> loadResult.file
            is TargetPlatformConfigurationLoadResult.Malformed -> TargetPlatformConfigurationsFile()
            is TargetPlatformConfigurationLoadResult.Missing -> TargetPlatformConfigurationsFile()
        }
        val dialog = TargetPlatformConfigurationDialog(project, initialFile)

        if (dialog.showAndGet()) {
            service.save(dialog.configurations())
            service.clearStaleSelectionIfNeeded()
        }
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isVisible = event.project != null
        event.presentation.isEnabled = event.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal data class TargetPlatformConfigurationPresentation(
    val text: String,
    val description: String?,
)

internal fun targetPlatformConfigurationPresentation(selectedName: String): TargetPlatformConfigurationPresentation {
    val trimmedName = selectedName.trim()
    if (trimmedName.isEmpty()) {
        return TargetPlatformConfigurationPresentation("Target & Platform", null)
    }

    val text = "Target & Platform: $trimmedName"
    return TargetPlatformConfigurationPresentation(text, text)
}
