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
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.SkippedResultImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.build.progress.BuildProgress
import com.intellij.build.progress.BuildProgressDescriptor
import com.intellij.build.progress.BuildProgressDescriptorImpl
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.project.Project
import java.util.EnumMap
import java.util.IdentityHashMap
import java.util.UUID

interface UnrealWorkflowPresenter {
    fun start(plan: UnrealExecutionPlan)

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

    data class Failure(val exitCode: Int) : UnrealActionResult

    data object Cancelled : UnrealActionResult
}

sealed interface UnrealPlanResult {
    data object Success : UnrealPlanResult

    data class Failure(
        val action: UnrealPlannedAction,
        val exitCode: Int,
    ) : UnrealPlanResult

    data object Cancelled : UnrealPlanResult
}

private class BuildViewUnrealWorkflowPresenter(
    private val project: Project,
) : UnrealWorkflowPresenter {
    private var plan: UnrealExecutionPlan? = null
    private var rootProgress: BuildProgress<BuildProgressDescriptor>? = null
    private val phaseStates = EnumMap<UnrealPhase, PhaseState>(UnrealPhase::class.java)
    private val actionProgresses = IdentityHashMap<UnrealPlannedAction, BuildProgress<BuildProgressDescriptor>>()

    override fun start(plan: UnrealExecutionPlan) {
        check(rootProgress == null) { "A workflow presentation can only be started once" }
        this.plan = plan

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
    }

    override fun actionStarted(action: UnrealPlannedAction) {
        check(!actionProgresses.containsKey(action)) { "Action has already started: $action" }
        val phaseState = phaseStates.getOrPut(action.phase) {
            PhaseState(root().startChildProgress(phaseTitle(action.phase)))
        }
        actionProgresses[action] = phaseState.progress.startChildProgress(actionTitle(action))
    }

    override fun output(action: UnrealPlannedAction, text: String, type: ProcessOutputType) {
        actionProgress(action).output(text, type)
    }

    override fun actionFinished(action: UnrealPlannedAction, result: UnrealActionResult) {
        val progress = actionProgresses.remove(action)
            ?: error("Action has not started: $action")
        progress.finish(actionResult(result))

        val phaseState = phaseStates.getValue(action.phase)
        phaseState.finishedActions++
        phaseState.result = combine(phaseState.result, result)
        val actionCount = requirePlan().phases
            .first { it.phase == action.phase }
            .actions
            .size
        if (phaseState.finishedActions == actionCount) {
            phaseState.progress.finish(actionResult(phaseState.result))
            phaseState.finished = true
        }
    }

    override fun finish(result: UnrealPlanResult) {
        val eventResult = planResult(result)
        actionProgresses.values.forEach { it.finish(eventResult) }
        actionProgresses.clear()
        phaseStates.values
            .filterNot { it.finished }
            .forEach {
                it.progress.finish(eventResult)
                it.finished = true
            }
        root().finish(eventResult)
    }

    private fun root(): BuildProgress<BuildProgressDescriptor> =
        rootProgress ?: error("Workflow presentation has not started")

    private fun requirePlan(): UnrealExecutionPlan =
        plan ?: error("Workflow presentation has not started")

    private fun actionProgress(action: UnrealPlannedAction): BuildProgress<BuildProgressDescriptor> =
        actionProgresses[action] ?: error("Action has not started: $action")

    private class PhaseState(
        val progress: BuildProgress<BuildProgressDescriptor>,
        var finishedActions: Int = 0,
        var result: UnrealActionResult = UnrealActionResult.Success,
        var finished: Boolean = false,
    )
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
        is UnrealActionResult.Failure -> FailureResultImpl("Process exited with code ${result.exitCode}")
        UnrealActionResult.Cancelled -> SkippedResultImpl()
    }

private fun planResult(result: UnrealPlanResult): EventResult =
    when (result) {
        UnrealPlanResult.Success -> SuccessResultImpl()
        is UnrealPlanResult.Failure -> FailureResultImpl(
            "${actionTitle(result.action)} exited with code ${result.exitCode}",
        )
        UnrealPlanResult.Cancelled -> SkippedResultImpl()
    }

private fun combine(current: UnrealActionResult, next: UnrealActionResult): UnrealActionResult =
    when {
        current is UnrealActionResult.Failure -> current
        next is UnrealActionResult.Failure -> next
        current == UnrealActionResult.Cancelled || next == UnrealActionResult.Cancelled -> UnrealActionResult.Cancelled
        else -> UnrealActionResult.Success
    }
