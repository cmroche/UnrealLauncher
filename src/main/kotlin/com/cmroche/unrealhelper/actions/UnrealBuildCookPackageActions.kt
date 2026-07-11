package com.cmroche.unrealhelper.actions

import com.cmroche.unrealhelper.config.SelectedTargetPlatformConfigurationResult
import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.config.TargetPlatformConfigurationService
import com.cmroche.unrealhelper.execution.UnrealWorkflowConflict
import com.cmroche.unrealhelper.execution.UnrealWorkflowExecution
import com.cmroche.unrealhelper.execution.UnrealWorkflowExecutionService
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.cmroche.unrealhelper.ui.UnrealWorkflowConflictDialog
import com.cmroche.unrealhelper.workflow.UnrealWorkflowPlanner
import com.cmroche.unrealhelper.workflow.UnrealWorkflowRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import java.nio.file.Path

internal class UnrealBuildAction : UnrealWorkflowAction(UnrealWorkflowRequest.BUILD)

internal class UnrealCookAction : UnrealWorkflowAction(UnrealWorkflowRequest.COOK)

internal class UnrealPackageAction : UnrealWorkflowAction(UnrealWorkflowRequest.PACKAGE)

internal abstract class UnrealWorkflowAction(
    private val request: UnrealWorkflowRequest,
) : AnAction(), DumbAware {
    final override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isVisible = project != null
        event.presentation.isEnabled = project != null && workflowActionEnabled(project.service<UnrealHelperSettings>().state)
    }

    final override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val settings = project.service<UnrealHelperSettings>()
        val selectedConfigurationResult = project.service<TargetPlatformConfigurationService>().selectedConfigurationResult()
        val selectedConfiguration = when (selectedConfigurationResult) {
            is SelectedTargetPlatformConfigurationResult.Valid -> selectedConfigurationResult.configuration
            else -> {
                UnrealActionMessages.selectedConfigurationError(selectedConfigurationResult)
                    ?.let { UnrealActionMessages.showError(project, it) }
                return
            }
        }

        val error = UnrealWorkflowSubmitter(
            execution = project.service<UnrealWorkflowExecutionService>(),
            confirmRestart = { UnrealWorkflowConflictDialog.confirm(project, it) },
        ).submit(request, selectedConfiguration, settings.state, project.basePath)
        if (error != null) UnrealActionMessages.showError(project, error)
    }

    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class UnrealWorkflowSubmitter(
    private val execution: UnrealWorkflowExecution,
    private val planner: UnrealWorkflowPlanner = UnrealWorkflowPlanner(),
    private val confirmRestart: (UnrealWorkflowConflict) -> Boolean = { false },
) {
    fun submit(
        request: UnrealWorkflowRequest,
        configuration: TargetPlatformConfiguration,
        state: UnrealHelperSettingsState,
        projectBasePath: String?,
    ): String? {
        val error = workflowValidationError(request, state, projectBasePath)
        if (error != null) return error

        val plan = planner.plan(request, configuration, state, projectBasePath)
        val conflict = execution.conflictFor(plan)
        if (conflict == null) {
            execution.start(plan)
        } else if (confirmRestart(conflict)) {
            execution.stopAndRestart(plan, conflict)
        }
        return null
    }
}

internal fun workflowActionEnabled(state: UnrealHelperSettingsState): Boolean = state.uprojectPath.isNotBlank()

internal fun workflowValidationError(
    request: UnrealWorkflowRequest,
    state: UnrealHelperSettingsState,
    projectBasePath: String?,
): String? = when (request) {
    UnrealWorkflowRequest.LAUNCH -> if (state.uprojectPath.isBlank()) ".uproject path is not configured" else null
    UnrealWorkflowRequest.BUILD,
    UnrealWorkflowRequest.COOK,
    UnrealWorkflowRequest.PACKAGE,
    -> buildCookPackageValidationError(state, projectBasePath)
}

internal fun buildCookPackageValidationError(
    state: UnrealHelperSettingsState,
    projectBasePath: String?,
): String? = when {
    state.uprojectPath.isBlank() -> ".uproject path is not configured"
    state.engineRoot.isBlank() ->
        "Engine root is not configured; set it in Tools > UnrealHelper before running Build, Cook, or Package."
    workspaceRootPath(state, projectBasePath) == null -> "Workspace root is not configured"
    else -> null
}

private fun workspaceRootPath(state: UnrealHelperSettingsState, projectBasePath: String?): Path? =
    if (state.workspaceRoot.isNotBlank()) {
        Path.of(state.workspaceRoot)
    } else {
        Path.of(state.uprojectPath).parent ?: projectBasePath?.takeIf { it.isNotBlank() }?.let(Path::of)
    }
