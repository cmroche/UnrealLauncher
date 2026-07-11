package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.workflow.BuildBatch
import com.cmroche.unrealhelper.workflow.Cook
import com.cmroche.unrealhelper.workflow.Launch
import com.cmroche.unrealhelper.workflow.Package
import com.cmroche.unrealhelper.workflow.Stage
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import com.cmroche.unrealhelper.workflow.UnrealExecutionPlan
import com.cmroche.unrealhelper.workflow.UnrealPlannedAction
import com.intellij.execution.process.ProcessOutputType
import java.util.ArrayDeque

enum class UnrealPlanState { IDLE, RUNNING, STOPPING, SUCCEEDED, FAILED, RESTART_BLOCKED }

data class UnrealExecutionQueueSnapshot(
    val state: UnrealPlanState,
    val running: UnrealPlannedAction?,
    val queued: List<UnrealPlannedAction>,
) {
    val isActive: Boolean
        get() = state == UnrealPlanState.RUNNING ||
            state == UnrealPlanState.STOPPING ||
            state == UnrealPlanState.RESTART_BLOCKED

    val runningNames: List<String>
        get() = listOfNotNull(running?.displayName())

    val queuedNames: List<String>
        get() = queued.map(UnrealPlannedAction::displayName)
}

fun interface UnrealPlannedActionExecutor {
    fun create(action: UnrealPlannedAction): UnrealWorkflowProcess
}

interface UnrealExecutionQueueCallbacks {
    fun launchStarted(action: Launch, process: UnrealWorkflowProcess) = Unit

    fun launchOutput(
        action: Launch,
        process: UnrealWorkflowProcess,
        text: String,
        outputType: ProcessOutputType,
    ) = Unit

    fun launchTerminated(action: Launch, process: UnrealWorkflowProcess, exitCode: Int) = Unit

    companion object {
        val NONE: UnrealExecutionQueueCallbacks = object : UnrealExecutionQueueCallbacks {}
    }
}

class UnrealExecutionQueue(
    private val executor: UnrealPlannedActionExecutor,
    private val presenterFactory: () -> UnrealWorkflowPresenter,
    private var callbacks: UnrealExecutionQueueCallbacks = UnrealExecutionQueueCallbacks.NONE,
) {
    private val lock = Any()
    private val queued = ArrayDeque<UnrealPlannedAction>()
    private var state = UnrealPlanState.IDLE
    private var running: RunningAction? = null
    private var presenter: UnrealWorkflowPresenter? = null
    private var pendingReplacement: UnrealExecutionPlan? = null
    private var pendingStopCompletion: (() -> Unit)? = null

    fun start(plan: UnrealExecutionPlan) = synchronized(lock) {
        check(!isActiveLocked()) { "An Unreal workflow is already active" }
        startLocked(plan)
    }

    fun snapshot(): UnrealExecutionQueueSnapshot = synchronized(lock) {
        UnrealExecutionQueueSnapshot(
            state = state,
            running = running?.action,
            queued = queued.toList(),
        )
    }

    internal fun setCallbacks(callbacks: UnrealExecutionQueueCallbacks) = synchronized(lock) {
        check(!isActiveLocked()) { "Cannot replace callbacks while an Unreal workflow is active" }
        this.callbacks = callbacks
    }

    internal fun blockRestart() = synchronized(lock) {
        blockRestartLocked()
    }

    fun stopForReplacement(plan: UnrealExecutionPlan) = synchronized(lock) {
        pendingStopCompletion = null
        pendingReplacement = plan
        queued.clear()

        val current = running
        if (current == null) {
            cancelCurrentPresentationLocked()
            startPendingReplacementLocked()
            return@synchronized
        }

        state = UnrealPlanState.STOPPING
        try {
            current.process.destroy()
        } catch (_: RuntimeException) {
            blockRestartLocked()
            return@synchronized
        }
    }

    fun stopAndWait(callback: () -> Unit) = synchronized(lock) {
        pendingReplacement = null
        pendingStopCompletion = callback
        queued.clear()

        val current = running
        if (current == null) {
            cancelCurrentPresentationLocked()
            state = UnrealPlanState.SUCCEEDED
            pendingStopCompletion = null
            callback()
            return@synchronized
        }

        state = UnrealPlanState.STOPPING
        try {
            current.process.destroy()
        } catch (_: RuntimeException) {
            blockRestartLocked()
        }
    }

    private fun startLocked(plan: UnrealExecutionPlan) {
        val newPresenter = presenterFactory()
        presenter = newPresenter
        queued.clear()
        plan.phases.forEach { queued.addAll(it.actions) }
        pendingReplacement = null
        state = UnrealPlanState.RUNNING
        newPresenter.start(plan)
        startNextLocked()
    }

    private fun startNextLocked() {
        if (state != UnrealPlanState.RUNNING || running != null) return
        val action = queued.pollFirst()
        if (action == null) {
            state = UnrealPlanState.SUCCEEDED
            presenter?.finish(UnrealPlanResult.Success)
            presenter = null
            return
        }

        presenter?.actionStarted(action)
        val process = try {
            executor.create(action)
        } catch (_: RuntimeException) {
            failLocked(action, PROCESS_START_FAILURE)
            return
        }
        val runningAction = RunningAction(action, process)
        running = runningAction

        try {
            process.start(listenerFor(runningAction))
        } catch (_: RuntimeException) {
            if (running === runningAction) {
                running = null
                failLocked(action, PROCESS_START_FAILURE)
            }
        }
    }

    private fun listenerFor(runningAction: RunningAction): UnrealWorkflowProcessListener =
        object : UnrealWorkflowProcessListener {
            override fun started() = synchronized(lock) {
                if (runningAction.started) return@synchronized
                runningAction.started = true
                val action = runningAction.action
                if (action is Launch && state == UnrealPlanState.RUNNING && running === runningAction) {
                    runningAction.handedOff = true
                    running = null
                    presenter?.actionFinished(action, UnrealActionResult.Success)
                    callbacks.launchStarted(action, runningAction.process)
                    startNextLocked()
                }
            }

            override fun output(text: String, outputType: ProcessOutputType) = synchronized(lock) {
                val action = runningAction.action
                if (action is Launch && runningAction.handedOff) {
                    callbacks.launchOutput(action, runningAction.process, text, outputType)
                } else if (running === runningAction) {
                    presenter?.output(action, text, outputType)
                }
            }

            override fun terminated(exitCode: Int) = synchronized(lock) {
                val action = runningAction.action
                if (action is Launch && runningAction.handedOff) {
                    callbacks.launchTerminated(action, runningAction.process, exitCode)
                    return@synchronized
                }
                if (running !== runningAction) return@synchronized

                if (state == UnrealPlanState.RESTART_BLOCKED) {
                    if (runningAction.process.isProcessTerminated) {
                        running = null
                        presenter?.actionFinished(action, UnrealActionResult.Cancelled)
                        presenter?.finish(UnrealPlanResult.Cancelled)
                        presenter = null
                    }
                    return@synchronized
                }

                if (state == UnrealPlanState.STOPPING) {
                    if (!runningAction.process.isProcessTerminated) {
                        blockRestartLocked()
                        return@synchronized
                    }
                    running = null
                    presenter?.actionFinished(action, UnrealActionResult.Cancelled)
                    cancelCurrentPresentationLocked()
                    val completion = pendingStopCompletion
                    pendingStopCompletion = null
                    if (completion == null) {
                        startPendingReplacementLocked()
                    } else {
                        state = UnrealPlanState.SUCCEEDED
                        completion()
                    }
                    return@synchronized
                }

                running = null
                if (exitCode == 0) {
                    presenter?.actionFinished(action, UnrealActionResult.Success)
                    startNextLocked()
                } else {
                    failLocked(action, exitCode)
                }
            }
        }

    private fun failLocked(action: UnrealPlannedAction, exitCode: Int) {
        queued.clear()
        state = UnrealPlanState.FAILED
        presenter?.actionFinished(action, UnrealActionResult.Failure(exitCode))
        presenter?.finish(UnrealPlanResult.Failure(action, exitCode))
        presenter = null
    }

    private fun cancelCurrentPresentationLocked() {
        if (state == UnrealPlanState.RUNNING || state == UnrealPlanState.STOPPING) {
            presenter?.finish(UnrealPlanResult.Cancelled)
        }
        presenter = null
    }

    private fun startPendingReplacementLocked() {
        val replacement = pendingReplacement ?: return
        pendingReplacement = null
        startLocked(replacement)
    }

    private fun blockRestartLocked() {
        state = UnrealPlanState.RESTART_BLOCKED
        pendingReplacement = null
        pendingStopCompletion = null
    }

    private fun isActiveLocked(): Boolean =
        state == UnrealPlanState.RUNNING ||
            state == UnrealPlanState.STOPPING ||
            state == UnrealPlanState.RESTART_BLOCKED

    private data class RunningAction(
        val action: UnrealPlannedAction,
        val process: UnrealWorkflowProcess,
        var started: Boolean = false,
        var handedOff: Boolean = false,
    )

    private companion object {
        const val PROCESS_START_FAILURE = -1
    }
}

internal fun UnrealPlannedAction.displayName(): String =
    when (this) {
        is BuildBatch -> "Build ${artifacts.joinToString(separator = "; ") { it.descriptor() }}"
        is Cook -> "Cook ${artifact.descriptor()} (${mode.name.lowercase()})"
        is Stage -> "Stage ${artifact.descriptor()}"
        is Package -> "Package ${artifact.descriptor()}"
        is Launch -> "Launch ${artifact.descriptor()} ($configurationName)"
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
