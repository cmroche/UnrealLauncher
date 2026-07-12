package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.workflow.BuildBatch
import com.cmroche.unrealhelper.workflow.Cook
import com.cmroche.unrealhelper.workflow.Launch
import com.cmroche.unrealhelper.workflow.Package
import com.cmroche.unrealhelper.workflow.Stage
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import com.cmroche.unrealhelper.workflow.UnrealExecutionPlan
import com.cmroche.unrealhelper.workflow.UnrealPhase
import com.cmroche.unrealhelper.workflow.UnrealPlannedAction
import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.EventResult
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.BuildEventPresentationData
import com.intellij.build.events.impl.OutputBuildEventImpl
import com.intellij.build.events.impl.PresentableBuildEventImpl
import com.intellij.build.events.impl.FinishEventImpl
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.SkippedResultImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.build.progress.BuildProgress
import com.intellij.build.progress.BuildProgressDescriptor
import com.intellij.build.progress.BuildProgressDescriptorImpl
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.project.Project
import com.intellij.util.ui.EmptyIcon
import java.util.IdentityHashMap
import java.util.UUID
import javax.swing.Icon

interface UnrealWorkflowPresenter {
    fun start(plan: UnrealExecutionPlan)

    fun actionQueued(action: UnrealPlannedAction) = Unit

    fun actionStarted(action: UnrealPlannedAction)

    fun output(action: UnrealPlannedAction, text: String, type: ProcessOutputType)

    fun actionFinished(action: UnrealPlannedAction, result: UnrealActionResult)

    fun finish(result: UnrealPlanResult)

    companion object {
        fun create(project: Project): UnrealWorkflowPresenter = BuildViewUnrealWorkflowPresenter(project)
    }
}

sealed interface UnrealActionResult {
    data object Success : UnrealActionResult

    data class Failure(
        val exitCode: Int,
        val detail: String? = null,
        val command: String? = null,
    ) : UnrealActionResult

    data object Cancelled : UnrealActionResult
}

sealed interface UnrealPlanResult {
    data object Success : UnrealPlanResult

    data class Failure(
        val action: UnrealPlannedAction,
        val exitCode: Int,
        val detail: String? = null,
        val command: String? = null,
        val cancelledActions: List<UnrealPlannedAction> = emptyList(),
    ) : UnrealPlanResult

    data object Cancelled : UnrealPlanResult
}

private class BuildViewUnrealWorkflowPresenter(
    private val project: Project,
) : UnrealWorkflowPresenter {
    private var rootProgress: BuildProgress<BuildProgressDescriptor>? = null
    private var treeEvents: UnrealBuildTreeEventAdapter? = null

    override fun start(plan: UnrealExecutionPlan) {
        check(rootProgress == null) { "A workflow presentation can only be started once" }
        val descriptor = DefaultBuildDescriptor(
            UUID.randomUUID(),
            workflowTitle(plan),
            project.basePath.orEmpty(),
            System.currentTimeMillis(),
        ).apply {
            isActivateToolWindowWhenAdded = true
            isActivateToolWindowWhenFailed = true
        }

        rootProgress = BuildViewManager.createBuildProgress(project)
            .start(BuildProgressDescriptorImpl(descriptor))
        val manager = project.getService(BuildViewManager::class.java)
        treeEvents = UnrealBuildTreeEventAdapter(descriptor.id, plan) { event ->
            manager.onEvent(descriptor.id, event)
        }
    }

    override fun actionStarted(action: UnrealPlannedAction) {
        events().started(action)
    }

    override fun actionQueued(action: UnrealPlannedAction) {
        events().queued(action)
    }

    override fun output(action: UnrealPlannedAction, text: String, type: ProcessOutputType) {
        events().output(action, text, type)
    }

    override fun actionFinished(action: UnrealPlannedAction, result: UnrealActionResult) {
        events().finished(action, result)
    }

    override fun finish(result: UnrealPlanResult) {
        val eventResult = planResult(result)
        root().finish(eventResult)
    }

    private fun root(): BuildProgress<BuildProgressDescriptor> =
        rootProgress ?: error("Workflow presentation has not started")

    private fun events(): UnrealBuildTreeEventAdapter =
        treeEvents ?: error("Workflow presentation has not started")
}

internal class UnrealBuildTreeEventAdapter(
    private val buildId: Any,
    private val plan: UnrealExecutionPlan,
    private val now: () -> Long = System::currentTimeMillis,
    private val emit: (BuildEvent) -> Unit,
) {
    private val states = UnrealWorkflowPresentationModel()
    private val actionIds = IdentityHashMap<UnrealPlannedAction, Any>()
    private val phaseIds = mutableMapOf<UnrealPhase, Any>()
    private val phaseResults = mutableMapOf<UnrealPhase, UnrealActionResult>()

    fun queued(action: UnrealPlannedAction) {
        states.queue(action)
        val phaseId = phaseIds.getOrPut(action.phase) {
            UUID.randomUUID().also { id -> emitNode(id, buildId, "Waiting: ${phaseTitle(action.phase)}") }
        }
        val actionId = UUID.randomUUID()
        actionIds[action] = actionId
        emitNode(actionId, phaseId, "Waiting: ${actionTitle(action)}")
    }

    fun started(action: UnrealPlannedAction) {
        states.start(action)
        emitNode(actionId(action), phaseId(action.phase), "Running: ${actionTitle(action)}")
        emitNode(phaseId(action.phase), buildId, "Running: ${phaseTitle(action.phase)}")
    }

    fun output(action: UnrealPlannedAction, text: String, type: ProcessOutputType) {
        emit(OutputBuildEventImpl(UUID.randomUUID(), actionId(action), now(), text, null, null, type))
    }

    fun finished(action: UnrealPlannedAction, result: UnrealActionResult) {
        states.finish(action, result)
        emit(
            FinishEventImpl(
                actionId(action),
                phaseId(action.phase),
                now(),
                "${status(result)}: ${actionTitle(action)}",
                null,
                null,
                actionResult(result),
            ),
        )
        phaseResults[action.phase] = combine(phaseResults[action.phase] ?: UnrealActionResult.Success, result)
        val phaseActions = plan.phases.first { it.phase == action.phase }.actions
        if (phaseActions.all { states.stateOf(it).isTerminal }) {
            val phaseResult = phaseResults.getValue(action.phase)
            emit(
                FinishEventImpl(
                    phaseId(action.phase),
                    buildId,
                    now(),
                    "${status(phaseResult)}: ${phaseTitle(action.phase)}",
                    null,
                    null,
                    actionResult(phaseResult),
                ),
            )
        }
    }

    private fun emitNode(id: Any, parentId: Any, message: String) {
        emit(PresentableBuildEventImpl(id, parentId, now(), message, null, null, NodePresentation))
    }

    private fun actionId(action: UnrealPlannedAction): Any =
        actionIds[action] ?: error("Action was not queued: $action")

    private fun phaseId(phase: UnrealPhase): Any =
        phaseIds[phase] ?: error("Phase was not queued: $phase")

    private fun status(result: UnrealActionResult): String = when (result) {
        UnrealActionResult.Success -> "Succeeded"
        is UnrealActionResult.Failure -> "Failed"
        UnrealActionResult.Cancelled -> "Cancelled"
    }

    private object NodePresentation : BuildEventPresentationData {
        override fun getNodeIcon(): Icon = EmptyIcon.create(16)
        override fun getExecutionConsole(): ExecutionConsole? = null
        override fun consoleToolbarActions(): ActionGroup? = null
    }
}

internal enum class UnrealPresentationActionState(val isTerminal: Boolean) {
    WAITING(false), RUNNING(false), SUCCEEDED(true), FAILED(true), CANCELLED(true),
}

internal class UnrealWorkflowPresentationModel {
    private val states = IdentityHashMap<UnrealPlannedAction, UnrealPresentationActionState>()

    fun queue(action: UnrealPlannedAction) {
        check(states.put(action, UnrealPresentationActionState.WAITING) == null) { "Action already queued: $action" }
    }

    fun start(action: UnrealPlannedAction) {
        check(states[action] == UnrealPresentationActionState.WAITING) { "Action is not waiting: $action" }
        check(runningActions().isEmpty()) { "Another action is already running" }
        states[action] = UnrealPresentationActionState.RUNNING
    }

    fun finish(action: UnrealPlannedAction, result: UnrealActionResult) {
        val current = states[action] ?: error("Action was not queued: $action")
        check(current == UnrealPresentationActionState.RUNNING ||
            (current == UnrealPresentationActionState.WAITING && result == UnrealActionResult.Cancelled))
        states[action] = when (result) {
            UnrealActionResult.Success -> UnrealPresentationActionState.SUCCEEDED
            is UnrealActionResult.Failure -> UnrealPresentationActionState.FAILED
            UnrealActionResult.Cancelled -> UnrealPresentationActionState.CANCELLED
        }
    }

    fun stateOf(action: UnrealPlannedAction): UnrealPresentationActionState =
        states[action] ?: error("Action was not queued: $action")

    fun runningActions(): List<UnrealPlannedAction> = states.entries
        .filter { it.value == UnrealPresentationActionState.RUNNING }
        .map { it.key }
}

private fun workflowTitle(plan: UnrealExecutionPlan): String =
    "Unreal ${plan.request.presentableName()} - ${plan.configurationName}"

private fun phaseTitle(phase: UnrealPhase): String = phase.presentableName()

private fun actionTitle(action: UnrealPlannedAction): String =
    when (action) {
        is BuildBatch -> "Build ${action.artifacts.joinToString(separator = "; ") { it.descriptor() }}"
        is Cook -> "Cook ${action.artifact.descriptor()} (${action.mode.name.lowercase()})"
        is Stage -> "Stage ${action.artifact.descriptor()}"
        is Package -> "Package ${action.artifact.descriptor()}"
        is Launch -> "Launch ${action.artifact.descriptor()} (${action.configurationName})"
    }

private fun UnrealArtifactKey.descriptor(): String =
    buildString {
        append(targetName)
        append(" [")
        append(targetType)
        append(", ")
        append(platform)
        append(", ")
        append(buildConfiguration)
        architecture?.let {
            append(", ")
            append(it)
        }
        append(']')
    }

private fun Enum<*>.presentableName(): String =
    name.lowercase().replaceFirstChar { it.titlecase() }

private fun actionResult(result: UnrealActionResult): EventResult =
    when (result) {
        UnrealActionResult.Success -> SuccessResultImpl()
        is UnrealActionResult.Failure -> FailureResultImpl(failureMessage(result.exitCode, result.detail, result.command))
        UnrealActionResult.Cancelled -> SkippedResultImpl()
    }

private fun planResult(result: UnrealPlanResult): EventResult =
    when (result) {
        UnrealPlanResult.Success -> SuccessResultImpl()
        is UnrealPlanResult.Failure -> FailureResultImpl(buildString {
            append(actionTitle(result.action)).append(": ")
            append(failureMessage(result.exitCode, result.detail, result.command))
            if (result.cancelledActions.isNotEmpty()) {
                append(". Cancelled: ")
                append(result.cancelledActions.joinToString { actionTitle(it) })
            }
        })
        UnrealPlanResult.Cancelled -> SkippedResultImpl()
    }

private fun combine(current: UnrealActionResult, next: UnrealActionResult): UnrealActionResult =
    when {
        current is UnrealActionResult.Failure -> current
        next is UnrealActionResult.Failure -> next
        current == UnrealActionResult.Cancelled || next == UnrealActionResult.Cancelled -> UnrealActionResult.Cancelled
        else -> UnrealActionResult.Success
    }

private fun failureMessage(exitCode: Int, detail: String?, command: String?): String = buildString {
    if (detail.isNullOrBlank()) append("Process exited with code $exitCode") else append(detail)
    if (!command.isNullOrBlank()) append("\nCommand: ").append(command)
}
