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
)

@Service(Service.Level.PROJECT)
class QuickLaunchProcessService private constructor(
    private val processFactory: QuickLaunchProcessFactory,
) : Disposable {
    constructor(project: Project) : this(RiderQuickLaunchProcessFactory(project))

    private val lock = Any()
    private val runningProcesses = mutableMapOf<QuickLaunchKey, TrackedLaunch>()

    fun launch(key: QuickLaunchKey, artifact: UnrealArtifactKey, commandLine: GeneralCommandLine) {
        stop(key)

        val title = title(key)
        val process = processFactory.create(commandLine, title)
        process.addTerminationListener {
            removeIfCurrent(key, process)
        }

        try {
            process.run()
            synchronized(lock) {
                if (!process.isProcessTerminated) {
                    runningProcesses[key] = TrackedLaunch(
                        info = RunningLaunchInfo(key, artifact, title),
                        process = process,
                    )
                }
            }
        } catch (throwable: Throwable) {
            removeIfCurrent(key, process)
            process.destroy()
            throw throwable
        }
    }

    internal fun registerRunningLaunch(
        key: QuickLaunchKey,
        artifact: UnrealArtifactKey,
        title: String,
        process: UnrealWorkflowProcess,
    ) {
        synchronized(lock) {
            if (!process.isProcessTerminated) {
                runningProcesses[key] = TrackedLaunch(
                    info = RunningLaunchInfo(key, artifact, title),
                    process = WorkflowTrackedLaunchProcess(process),
                )
            }
        }
    }

    internal fun runningLaunchTerminated(key: QuickLaunchKey, process: UnrealWorkflowProcess) {
        val tracked = synchronized(lock) {
            runningProcesses[key]
                ?.takeIf { it.process.identity === process }
                ?.also { runningProcesses.remove(key) }
        }
        (tracked?.process as? WorkflowTrackedLaunchProcess)?.terminated()
    }

    fun stop(key: QuickLaunchKey) {
        val process = synchronized(lock) {
            runningProcesses[key]?.process
        }
        process?.destroy()
    }

    fun stopAndWait(keys: Set<QuickLaunchKey>, callback: () -> Unit) {
        val processes = synchronized(lock) {
            keys.mapNotNull { runningProcesses[it]?.process }.distinct()
        }
        if (processes.isEmpty()) {
            callback()
            return
        }

        val waitLock = Any()
        val remaining = processes.toMutableSet()
        var completed = false
        fun processTerminated(process: TrackedLaunchProcess) {
            val shouldComplete = synchronized(waitLock) {
                remaining.remove(process)
                if (!completed && remaining.isEmpty()) {
                    completed = true
                    true
                } else {
                    false
                }
            }
            if (shouldComplete) callback()
        }

        processes.forEach { process ->
            process.addTerminationListener { processTerminated(process) }
            if (process.isProcessTerminated) processTerminated(process)
        }
        processes.forEach { process ->
            if (!process.isProcessTerminated) process.destroy()
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
            runningProcesses[key]?.process?.isProcessTerminated == false
        }

    fun runningKeys(): Set<QuickLaunchKey> =
        synchronized(lock) {
            runningProcesses
                .filterValues { !it.process.isProcessTerminated }
                .keys
                .toSet()
        }

    fun runningLaunches(): List<RunningLaunchInfo> = synchronized(lock) {
        runningProcesses.values.mapNotNull { tracked ->
            tracked.info.takeIf { !tracked.process.isProcessTerminated }
        }
    }

    private fun removeIfCurrent(key: QuickLaunchKey, process: TrackedLaunchProcess) {
        synchronized(lock) {
            if (runningProcesses[key]?.process === process) {
                runningProcesses.remove(key)
            }
        }
    }

    private fun title(key: QuickLaunchKey): String =
        "Unreal ${key.configurationName} ${key.entryIndex + 1}: ${key.targetName} ${key.targetType} ${key.platform}"

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
