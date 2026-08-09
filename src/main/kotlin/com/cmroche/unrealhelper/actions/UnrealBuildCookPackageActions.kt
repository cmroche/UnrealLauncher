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
import com.cmroche.unrealhelper.workflow.UnrealExecutionPlan
import com.cmroche.unrealhelper.workflow.UnrealWorkflowPreflightResult
import com.cmroche.unrealhelper.workflow.UnrealWorkflowPreflightValidator
import com.cmroche.unrealhelper.workflow.UnrealWorkflowRequest
import com.cmroche.unrealhelper.workflow.incompatiblePlatformErrors
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

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
        val error = submitSelectedWorkflow(selectedConfigurationResult) { selectedConfiguration ->
            UnrealWorkflowSubmitter(
                execution = project.service<UnrealWorkflowExecutionService>(),
                platformCompatibilityErrors = { configuration, state ->
                    incompatiblePlatformErrors(
                        configuration,
                        state,
                        RiderUnrealPlatformCompatibility.availableConfigurations(project),
                    )
                },
                confirmRestart = { UnrealWorkflowConflictDialog.confirm(project, it) },
            ).submit(request, selectedConfiguration, settings.state, project.basePath)
        }
        if (error != null) UnrealActionMessages.showError(project, error)
    }

    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class UnrealWorkflowSubmitter(
    private val execution: UnrealWorkflowExecution,
    private val planner: (
        UnrealWorkflowRequest,
        TargetPlatformConfiguration,
        UnrealHelperSettingsState,
        String?,
    ) -> UnrealExecutionPlan = UnrealWorkflowPlanner()::plan,
    private val preflight: (
        UnrealWorkflowRequest,
        TargetPlatformConfiguration,
        UnrealHelperSettingsState,
        String?,
    ) -> UnrealWorkflowPreflightResult = UnrealWorkflowPreflightValidator(plan = planner)::prepare,
    private val platformCompatibilityErrors: (
        TargetPlatformConfiguration,
        UnrealHelperSettingsState,
    ) -> List<String> = { _, _ -> emptyList() },
    private val confirmRestart: (UnrealWorkflowConflict) -> Boolean = { false },
) {
    fun submit(
        request: UnrealWorkflowRequest,
        configuration: TargetPlatformConfiguration,
        state: UnrealHelperSettingsState,
        projectBasePath: String?,
    ): String? {
        UnrealActionMessages.preflightError(
            configuration.name,
            platformCompatibilityErrors(configuration, state),
        )?.let { return it }

        val result = preflight(request, configuration, state, projectBasePath)
        UnrealActionMessages.preflightError(configuration.name, result.errors)?.let { return it }
        val plan = result.plan ?: return UnrealActionMessages.preflightError(
            configuration.name,
            listOf("Workflow plan could not be created"),
        )
        val conflict = execution.conflictFor(plan)
        if (conflict == null) {
            execution.start(plan)
        } else if (confirmRestart(conflict)) {
            execution.stopAndRestart(plan, conflict)
        }
        return null
    }
}

internal fun submitSelectedWorkflow(
    result: SelectedTargetPlatformConfigurationResult,
    submit: (TargetPlatformConfiguration) -> String?,
): String? = when (result) {
    is SelectedTargetPlatformConfigurationResult.Valid -> submit(result.configuration)
    is SelectedTargetPlatformConfigurationResult.InvalidEntries -> submit(result.configuration)
    else -> UnrealActionMessages.selectedConfigurationError(result)
}

internal fun workflowActionEnabled(state: UnrealHelperSettingsState): Boolean = state.uprojectPath.isNotBlank()
