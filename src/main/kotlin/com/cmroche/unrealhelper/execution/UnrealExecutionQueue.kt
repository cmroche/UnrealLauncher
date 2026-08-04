package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.workflow.BuildBatch
import com.cmroche.unrealhelper.workflow.Cook
import com.cmroche.unrealhelper.workflow.Launch
import com.cmroche.unrealhelper.workflow.Package
import com.cmroche.unrealhelper.workflow.Stage
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import com.cmroche.unrealhelper.workflow.UnrealExecutionPlan
import com.cmroche.unrealhelper.workflow.UnrealExecutionEnvironment
import com.cmroche.unrealhelper.workflow.UnrealPlannedAction
import com.intellij.execution.process.ProcessOutputType
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

enum class UnrealPlanState { IDLE, RUNNING, STOPPING, SUCCEEDED, FAILED, RESTART_BLOCKED }

internal sealed interface UnrealQueueStopResult {
    data object Completed : UnrealQueueStopResult
    data class Failed(val message: String) : UnrealQueueStopResult
}

fun interface UnrealRestartTimeoutScheduler {
    fun schedule(task: () -> Unit)

    companion object {
        val NONE = UnrealRestartTimeoutScheduler { }
        val DEFAULT = UnrealRestartTimeoutScheduler { task ->
            AppExecutorUtil.getAppScheduledExecutorService().schedule(task, 30, TimeUnit.SECONDS)
        }
    }
}

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
    fun create(action: UnrealPlannedAction, environment: UnrealExecutionEnvironment): UnrealWorkflowProcess
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

    fun workflowFailed(result: UnrealPlanResult.Failure) = Unit

    fun restartFailed(message: String) = Unit

    companion object {
        val NONE: UnrealExecutionQueueCallbacks = object : UnrealExecutionQueueCallbacks {}
    }
}

class UnrealExecutionQueue(
    private val executor: UnrealPlannedActionExecutor,
    private val presenterFactory: () -> UnrealWorkflowPresenter,
    private var callbacks: UnrealExecutionQueueCallbacks = UnrealExecutionQueueCallbacks.NONE,
    private val timeoutScheduler: UnrealRestartTimeoutScheduler = UnrealRestartTimeoutScheduler.DEFAULT,
) {
    private val lock = Any()
    private val queued = ArrayDeque<QueuedAction>()
    private var state = UnrealPlanState.IDLE
    private var running: RunningAction? = null
    private var presenter: UnrealWorkflowPresenter? = null
    private var pendingReplacement: UnrealExecutionPlan? = null
    private var pendingStopCompletion: ((UnrealQueueStopResult) -> Unit)? = null
    private var restartAwaitingExternalRecovery = false

    fun start(plan: UnrealExecutionPlan) = synchronized(lock) {
        check(!isActiveLocked()) { "An Unreal workflow is already active" }
        startLocked(plan)
    }

    fun snapshot(): UnrealExecutionQueueSnapshot = synchronized(lock) {
        UnrealExecutionQueueSnapshot(
            state = state,
            running = running?.action,
            queued = queued.map { it.action },
        )
    }

    internal fun setCallbacks(callbacks: UnrealExecutionQueueCallbacks) = synchronized(lock) {
        check(!isActiveLocked()) { "Cannot replace callbacks while an Unreal workflow is active" }
        this.callbacks = callbacks
    }

    internal fun blockRestart(message: String = "Could not stop all running Unreal processes") = synchronized(lock) {
        blockRestartLocked(message, awaitExternalRecovery = true)
    }

    internal fun recoverBlockedRestart() = synchronized(lock) {
        restartAwaitingExternalRecovery = false
        if (state == UnrealPlanState.RESTART_BLOCKED && running == null) state = UnrealPlanState.FAILED
    }

    fun stopForReplacement(plan: UnrealExecutionPlan) = synchronized(lock) {
        pendingStopCompletion = null
        pendingReplacement = plan
        cancelQueuedLocked()

        val current = running
        if (current == null) {
            cancelCurrentPresentationLocked()
            startPendingReplacementLocked()
            return@synchronized
        }

        state = UnrealPlanState.STOPPING
        try {
            current.process.destroy()
            scheduleRestartTimeoutLocked(current)
        } catch (exception: RuntimeException) {
            blockRestartLocked("Could not stop ${current.action.displayName()}: ${exception.message}")
            return@synchronized
        }
    }

    fun stopAndWait(callback: () -> Unit) = stopAndWaitResult { result ->
        if (result == UnrealQueueStopResult.Completed) callback()
    }

    internal fun stopAndWaitResult(callback: (UnrealQueueStopResult) -> Unit) = synchronized(lock) {
        pendingReplacement = null
        pendingStopCompletion = callback
        cancelQueuedLocked()

        val current = running
        if (current == null) {
            cancelCurrentPresentationLocked()
            state = UnrealPlanState.SUCCEEDED
            pendingStopCompletion = null
            callback(UnrealQueueStopResult.Completed)
            return@synchronized
        }

        state = UnrealPlanState.STOPPING
        try {
            current.process.destroy()
            scheduleRestartTimeoutLocked(current)
        } catch (exception: RuntimeException) {
            blockRestartLocked("Could not stop ${current.action.displayName()}: ${exception.message}")
        }
    }

    private fun startLocked(plan: UnrealExecutionPlan) {
        val newPresenter = presenterFactory()
        presenter = newPresenter
        queued.clear()
        plan.phases.forEach { phase ->
            queued.addAll(phase.actions.map { QueuedAction(it, plan.environment) })
        }
        pendingReplacement = null
        state = UnrealPlanState.RUNNING
        newPresenter.start(plan)
        queued.forEach { newPresenter.actionQueued(it.action) }
        startNextLocked()
    }

    private fun startNextLocked() {
        if (state != UnrealPlanState.RUNNING || running != null) return
        val queuedAction = queued.pollFirst()
        if (queuedAction == null) {
            state = UnrealPlanState.SUCCEEDED
            presenter?.finish(UnrealPlanResult.Success)
            presenter = null
            return
        }
        val action = queuedAction.action

        presenter?.actionStarted(action)
        val process = try {
            executor.create(action, queuedAction.environment)
        } catch (exception: RuntimeException) {
            failLocked(action, PROCESS_START_FAILURE, exceptionDetail(action, exception))
            return
        }
        val runningAction = RunningAction(action, process)
        running = runningAction

        try {
            process.start(listenerFor(runningAction))
        } catch (exception: RuntimeException) {
            if (running === runningAction) {
                running = null
                failLocked(
                    action,
                    PROCESS_START_FAILURE,
                    exceptionDetail(action, exception),
                    process.commandDescription,
                )
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
                        if (!restartAwaitingExternalRecovery) state = UnrealPlanState.FAILED
                    }
                    return@synchronized
                }

                if (state == UnrealPlanState.STOPPING) {
                    if (!runningAction.process.isProcessTerminated) {
                        blockRestartLocked("Stop callback completed but ${action.displayName()} is still running")
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
                        completion(UnrealQueueStopResult.Completed)
                    }
                    return@synchronized
                }

                running = null
                if (exitCode == 0) {
                    presenter?.actionFinished(action, UnrealActionResult.Success)
                    startNextLocked()
                } else {
                    failLocked(action, exitCode, command = runningAction.process.commandDescription)
                }
            }
        }

    private fun failLocked(
        action: UnrealPlannedAction,
        exitCode: Int,
        detail: String? = null,
        command: String? = null,
    ) {
        val cancelled = queued.map { it.action }
        if (!detail.isNullOrBlank()) presenter?.output(action, "$detail\n", ProcessOutputType.STDERR)
        cancelQueuedLocked()
        state = UnrealPlanState.FAILED
        presenter?.actionFinished(action, UnrealActionResult.Failure(exitCode, detail, command))
        val result = UnrealPlanResult.Failure(action, exitCode, detail, command, cancelled)
        presenter?.finish(result)
        callbacks.workflowFailed(result)
        presenter = null
    }

    private fun cancelQueuedLocked() {
        queued.forEach { presenter?.actionFinished(it.action, UnrealActionResult.Cancelled) }
        queued.clear()
    }

    private fun exceptionDetail(action: UnrealPlannedAction, exception: RuntimeException): String =
        "${exception::class.simpleName}: ${exception.message.orEmpty()}\nAction: ${action.displayName()}"

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

    private fun blockRestartLocked(message: String, awaitExternalRecovery: Boolean = false) {
        val stopCompletion = pendingStopCompletion
        state = UnrealPlanState.RESTART_BLOCKED
        restartAwaitingExternalRecovery = awaitExternalRecovery
        pendingReplacement = null
        pendingStopCompletion = null
        callbacks.restartFailed(message)
        stopCompletion?.invoke(UnrealQueueStopResult.Failed(message))
    }

    private fun scheduleRestartTimeoutLocked(current: RunningAction) {
        timeoutScheduler.schedule {
            synchronized(lock) {
                if (state == UnrealPlanState.STOPPING && running === current && !current.process.isProcessTerminated) {
                    blockRestartLocked("Timed out waiting for ${current.action.displayName()} to stop")
                }
            }
        }
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

    private data class QueuedAction(
        val action: UnrealPlannedAction,
        val environment: UnrealExecutionEnvironment,
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
