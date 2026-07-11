package com.cmroche.unrealhelper.launch

import com.cmroche.unrealhelper.execution.UnrealWorkflowProcess
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
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
    private val processFactory: QuickLaunchProcessFactory,
) : Disposable {
    constructor(project: Project) : this(RiderQuickLaunchProcessFactory(project))

    private val lock = Any()
    private val runningProcesses = mutableMapOf<QuickLaunchInstanceId, TrackedLaunch>()
    private val workflowProcesses = IdentityHashMap<UnrealWorkflowProcess, QuickLaunchInstanceId>()
    private var nextInstanceId = 1L

    fun launch(key: QuickLaunchKey, artifact: UnrealArtifactKey, commandLine: GeneralCommandLine) {
        replaceDirectLaunch(key)

        val title = title(key)
        val process = processFactory.create(commandLine, title)
        val instanceId = synchronized(lock) { nextInstanceIdLocked() }
        process.addTerminationListener {
            remove(instanceId, process)
        }

        try {
            process.run()
            synchronized(lock) {
                if (!process.isProcessTerminated) {
                    runningProcesses[instanceId] = TrackedLaunch(
                        info = RunningLaunchInfo(key, artifact, title, instanceId),
                        process = process,
                    )
                }
            }
        } catch (throwable: Throwable) {
            remove(instanceId, process)
            process.destroy()
            throw throwable
        }
    }

    private fun replaceDirectLaunch(key: QuickLaunchKey) {
        val replaced = synchronized(lock) {
            runningProcesses
                .filterValues { it.info.key == key && it.process is QuickLaunchProcess }
                .toMap()
                .also { launches -> launches.keys.forEach(runningProcesses::remove) }
                .values
                .map { it.process }
        }
        replaced.forEach { it.destroy() }
    }

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
            val completion = synchronized(waitLock) {
                remaining.remove(process)
                if (destroyRequestsFinished && remaining.isEmpty()) {
                    completeLocked(QuickLaunchStopResult.Completed)
                } else {
                    null
                }
            }
            completion?.let(callback)
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

    private fun remove(instanceId: QuickLaunchInstanceId, process: TrackedLaunchProcess) {
        synchronized(lock) {
            if (runningProcesses[instanceId]?.process === process) {
                runningProcesses.remove(instanceId)
            }
        }
    }

    private fun title(key: QuickLaunchKey): String =
        "Unreal ${key.configurationName} ${key.entryIndex + 1}: ${key.targetName} ${key.targetType} ${key.platform}"

    private fun nextInstanceIdLocked(): QuickLaunchInstanceId = QuickLaunchInstanceId(nextInstanceId++)

    private data class TrackedLaunch(
        val info: RunningLaunchInfo,
        val process: TrackedLaunchProcess,
    )

    companion object {
        internal fun createForTest(processFactory: QuickLaunchProcessFactory): QuickLaunchProcessService =
            QuickLaunchProcessService(processFactory)
    }
}

internal interface QuickLaunchProcessFactory {
    fun create(commandLine: GeneralCommandLine, title: String): QuickLaunchProcess
}

internal interface TrackedLaunchProcess {
    val identity: Any
    val isProcessTerminated: Boolean

    fun destroy()

    fun addTerminationListener(listener: () -> Unit)
}

internal interface QuickLaunchProcess : TrackedLaunchProcess {
    override val identity: Any
        get() = this

    fun run()
}

private class WorkflowTrackedLaunchProcess(
    private val process: UnrealWorkflowProcess,
) : TrackedLaunchProcess {
    private val lock = Any()
    private val terminationListeners = mutableListOf<() -> Unit>()

    override val identity: Any
        get() = process

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

private class RiderQuickLaunchProcessFactory(private val project: Project) : QuickLaunchProcessFactory {
    override fun create(commandLine: GeneralCommandLine, title: String): QuickLaunchProcess =
        RiderQuickLaunchProcess(
            project = project,
            handler = OSProcessHandler(commandLine),
            title = title,
        )
}

private class RiderQuickLaunchProcess(
    private val project: Project,
    private val handler: OSProcessHandler,
    private val title: String,
) : QuickLaunchProcess {
    private val lock = Any()

    override val isProcessTerminated: Boolean
        get() = handler.isProcessTerminated

    override fun destroy() {
        synchronized(lock) {
            ensureStartNotified()
            if (!handler.isProcessTerminated && !handler.isProcessTerminating) {
                handler.destroyProcess()
            }
        }
    }

    override fun addTerminationListener(listener: () -> Unit) {
        handler.addProcessListener(
            object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    listener()
                }
            },
        )
    }

    override fun run() {
        RunContentExecutor(project, handler)
            .withTitle(title)
            .withStop(Runnable { destroy() }, Computable { canStop() })
            .withActivateToolWindow(true)
            .withFocusToolWindow(true)
            .run()
    }

    private fun ensureStartNotified() {
        if (!handler.isStartNotified) {
            handler.startNotify()
        }
    }

    private fun canStop(): Boolean =
        !handler.isProcessTerminated && !handler.isProcessTerminating
}
