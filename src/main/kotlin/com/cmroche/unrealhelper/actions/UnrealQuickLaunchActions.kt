package com.cmroche.unrealhelper.actions

import com.cmroche.unrealhelper.config.SelectedTargetPlatformConfigurationResult
import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.config.TargetPlatformConfigurationService
import com.cmroche.unrealhelper.config.resolveConfigurationEntries
import com.cmroche.unrealhelper.launch.QuickLaunchKey
import com.cmroche.unrealhelper.launch.QuickLaunchProcessService
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.cmroche.unrealhelper.workflow.UnrealWorkflowRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction

internal class UnrealLaunchAction : UnrealWorkflowAction(UnrealWorkflowRequest.LAUNCH)

internal class UnrealDebugAction : UnrealWorkflowAction(UnrealWorkflowRequest.DEBUG)

internal class UnrealStopLaunchAction : DumbAwareAction("Stop", "Stop selected UnrealHelper launches", null) {
    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isVisible = project != null
        if (project == null) {
            event.presentation.isEnabled = false
            return
        }

        val state = project.service<UnrealHelperSettings>().state
        val selected = project.service<TargetPlatformConfigurationService>().selectedConfigurationResult()
        val selectedKeys = if (selected is SelectedTargetPlatformConfigurationResult.Valid) {
            selectedQuickLaunchKeys(selected.configuration, state)
        } else {
            emptyList()
        }
        event.presentation.isEnabled = stopLaunchSelection(
            selectedKeys,
            project.service<QuickLaunchProcessService>().runningKeys(),
        ).isNotEmpty()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val processService = project.service<QuickLaunchProcessService>()
        val state = project.service<UnrealHelperSettings>().state
        val selected = project.service<TargetPlatformConfigurationService>().selectedConfigurationResult()
        if (selected !is SelectedTargetPlatformConfigurationResult.Valid) return

        stopLaunchSelection(
            selectedKeys = selectedQuickLaunchKeys(selected.configuration, state),
            runningKeys = processService.runningKeys(),
        ).forEach(processService::stop)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal fun selectedQuickLaunchKeys(
    configuration: TargetPlatformConfiguration,
    state: UnrealHelperSettingsState,
): List<QuickLaunchKey> = resolveConfigurationEntries(configuration, state).entries.map { entry ->
    QuickLaunchKey(
        configurationName = configuration.name,
        entryIndex = entry.index,
        targetName = entry.targetName,
        targetType = entry.targetType,
        platform = entry.platform,
    )
}

internal fun stopLaunchSelection(
    selectedKeys: Collection<QuickLaunchKey>,
    runningKeys: Set<QuickLaunchKey>,
): Set<QuickLaunchKey> = selectedKeys.toSet().intersect(runningKeys)
