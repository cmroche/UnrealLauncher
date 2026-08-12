package com.cmroche.unrealhelper.launch

import com.cmroche.unrealhelper.execution.UnrealWorkflowProcess
import com.cmroche.unrealhelper.execution.UnrealRestartTimeoutScheduler
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.IdentityHashMap

data class QuickLaunchKey(
    val configurationName: String,
    val entryIndex: Int,
    val targetName: String,
    val targetType: String,
    val platform: String,
)

data class RunningLaunchInfo(
    val key: QuickLaunchKey,
    val artifact: UnrealArtifactKey,
    val title: String,
    val instanceId: QuickLaunchInstanceId,
)

@JvmInline
value class QuickLaunchInstanceId internal constructor(internal val value: Long)

sealed class QuickLaunchStopResult {
    data object Completed : QuickLaunchStopResult()

    data class Failed(val cause: RuntimeException) : QuickLaunchStopResult()
}

@Service(Service.Level.PROJECT)
class QuickLaunchProcessService private constructor(
    private val timeoutScheduler: UnrealRestartTimeoutScheduler,
) : Disposable {
    constructor(@Suppress("UNUSED_PARAMETER") project: Project) : this(UnrealRestartTimeoutScheduler.DEFAULT)

    private val lock = Any()
    private val runningProcesses = mutableMapOf<QuickLaunchInstanceId, TrackedLaunch>()
    private val workflowProcesses = IdentityHashMap<UnrealWorkflowProcess, QuickLaunchInstanceId>()
    private var nextInstanceId = 1L

    internal fun registerRunningLaunch(
        key: QuickLaunchKey,
        artifact: UnrealArtifactKey,
        title: String,
        process: UnrealWorkflowProcess,
    ) {
        synchronized(lock) {
            if (!process.isProcessTerminated) {
                val trackedProcess = WorkflowTrackedLaunchProcess(process)
                val instanceId = nextInstanceIdLocked()
                runningProcesses[instanceId] = TrackedLaunch(
                    info = RunningLaunchInfo(key, artifact, title, instanceId),
                    process = trackedProcess,
                )
                workflowProcesses[process] = instanceId
            }
        }
    }

    internal fun runningLaunchTerminated(process: UnrealWorkflowProcess) {
        val trackedProcess = synchronized(lock) {
            val instanceId = workflowProcesses.remove(process) ?: return@synchronized null
            runningProcesses.remove(instanceId)?.process as? WorkflowTrackedLaunchProcess
        }
        trackedProcess?.terminated()
    }

    fun stop(key: QuickLaunchKey) {
        val processes = synchronized(lock) {
            runningProcesses.values.filter { it.info.key == key }.map { it.process }
        }
        processes.forEach { it.destroy() }
    }

    fun stopAndWait(
        launches: Collection<RunningLaunchInfo>,
        callback: (QuickLaunchStopResult) -> Unit,
    ) = stopAndWait(launches, callback, recovered = {})

    fun stopAndWait(
        launches: Collection<RunningLaunchInfo>,
        callback: (QuickLaunchStopResult) -> Unit,
        recovered: () -> Unit,
    ) {
        val processes = synchronized(lock) {
            launches.mapNotNull { selected ->
                runningProcesses[selected.instanceId]
                    ?.takeIf { it.info.key == selected.key }
                    ?.process
            }.distinct()
        }
        if (processes.isEmpty()) {
            callback(QuickLaunchStopResult.Completed)
            return
        }

        val waitLock = Any()
        val remaining = processes.toMutableSet()
        var destroyRequestsFinished = false
        var result: QuickLaunchStopResult? = null
        fun completeLocked(completion: QuickLaunchStopResult): QuickLaunchStopResult? {
            if (result != null) return null
            result = completion
            return completion
        }
        fun processTerminated(process: TrackedLaunchProcess) {
            val (completion, recovery) = synchronized(waitLock) {
                remaining.remove(process)
                if (destroyRequestsFinished && remaining.isEmpty()) {
                    if (result is QuickLaunchStopResult.Failed) {
                        null to recovered
                    } else {
                        completeLocked(QuickLaunchStopResult.Completed) to null
                    }
                } else {
                    null to null
                }
            }
            completion?.let(callback)
            recovery?.invoke()
        }

        processes.forEach { process ->
            process.addTerminationListener { processTerminated(process) }
            if (process.isProcessTerminated) processTerminated(process)
        }
        var failure: RuntimeException? = null
        processes.forEach { process ->
            if (!process.isProcessTerminated) {
                try {
                    process.destroy()
                } catch (exception: RuntimeException) {
                    if (failure == null) failure = exception
                }
            }
        }
        val completion = synchronized(waitLock) {
            destroyRequestsFinished = true
            when {
                failure != null -> completeLocked(QuickLaunchStopResult.Failed(failure))
                remaining.isEmpty() -> completeLocked(QuickLaunchStopResult.Completed)
                else -> null
            }
        }
        completion?.let(callback)
        timeoutScheduler.schedule {
            val timedOut = synchronized(waitLock) {
                if (result == null && remaining.isNotEmpty()) {
                    completeLocked(
                        QuickLaunchStopResult.Failed(
                            IllegalStateException("Timed out waiting for ${remaining.size} Unreal launch process(es) to stop"),
                        ),
                    )
                } else {
                    null
                }
            }
            timedOut?.let(callback)
        }
    }

    fun stopAll() {
        val processes = synchronized(lock) {
            runningProcesses.values.map { it.process }.distinct()
        }
        processes.forEach { it.destroy() }
    }

    override fun dispose() {
        stopAll()
    }

    fun isRunning(key: QuickLaunchKey): Boolean =
        synchronized(lock) {
            runningProcesses.values.any { it.info.key == key && !it.process.isProcessTerminated }
        }

    fun runningKeys(): Set<QuickLaunchKey> =
        synchronized(lock) {
            runningProcesses
                .values
                .filter { !it.process.isProcessTerminated }
                .map { it.info.key }
                .toSet()
        }

    fun runningLaunches(): List<RunningLaunchInfo> = synchronized(lock) {
        runningProcesses.values.mapNotNull { tracked ->
            tracked.info.takeIf { !tracked.process.isProcessTerminated }
        }
    }

    private fun nextInstanceIdLocked(): QuickLaunchInstanceId = QuickLaunchInstanceId(nextInstanceId++)

    private data class TrackedLaunch(
        val info: RunningLaunchInfo,
        val process: TrackedLaunchProcess,
    )

    companion object {
        internal fun createForTest(
            timeoutScheduler: UnrealRestartTimeoutScheduler = UnrealRestartTimeoutScheduler.NONE,
        ): QuickLaunchProcessService = QuickLaunchProcessService(timeoutScheduler)
    }
}

private interface TrackedLaunchProcess {
    val isProcessTerminated: Boolean

    fun destroy()

    fun addTerminationListener(listener: () -> Unit)
}

private class WorkflowTrackedLaunchProcess(
    private val process: UnrealWorkflowProcess,
) : TrackedLaunchProcess {
    private val lock = Any()
    private val terminationListeners = mutableListOf<() -> Unit>()

    override val isProcessTerminated: Boolean
        get() = process.isProcessTerminated

    override fun destroy() = process.destroy()

    override fun addTerminationListener(listener: () -> Unit) {
        synchronized(lock) {
            terminationListeners += listener
        }
    }

    fun terminated() {
        val listeners = synchronized(lock) {
            terminationListeners.toList().also { terminationListeners.clear() }
        }
        listeners.forEach { it() }
    }
}
