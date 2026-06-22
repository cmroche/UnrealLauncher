package com.cmroche.unrealhelper.launch

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

data class QuickLaunchKey(val targetType: String, val platform: String)

@Service(Service.Level.PROJECT)
class QuickLaunchProcessService private constructor(
    private val processFactory: QuickLaunchProcessFactory,
) {
    constructor(project: Project) : this(RiderQuickLaunchProcessFactory(project))

    private val lock = Any()
    private val runningProcesses = mutableMapOf<QuickLaunchKey, QuickLaunchProcess>()

    fun launch(key: QuickLaunchKey, commandLine: GeneralCommandLine) {
        stop(key)

        val process = processFactory.create(commandLine, title(key))
        process.addTerminationListener {
            removeIfCurrent(key, process)
        }

        synchronized(lock) {
            runningProcesses[key] = process
        }

        try {
            process.run()
        } catch (throwable: Throwable) {
            removeIfCurrent(key, process)
            process.destroy()
            throw throwable
        }
    }

    fun stop(key: QuickLaunchKey) {
        val process = synchronized(lock) {
            runningProcesses.remove(key)
        }
        process?.destroy()
    }

    fun stopAll() {
        val processes = synchronized(lock) {
            runningProcesses.values.toList().also {
                runningProcesses.clear()
            }
        }
        processes.forEach { it.destroy() }
    }

    fun isRunning(key: QuickLaunchKey): Boolean =
        synchronized(lock) {
            runningProcesses[key]?.isProcessTerminated == false
        }

    fun runningKeys(): Set<QuickLaunchKey> =
        synchronized(lock) {
            runningProcesses
                .filterValues { !it.isProcessTerminated }
                .keys
                .toSet()
        }

    private fun removeIfCurrent(key: QuickLaunchKey, process: QuickLaunchProcess) {
        synchronized(lock) {
            if (runningProcesses[key] === process) {
                runningProcesses.remove(key)
            }
        }
    }

    private fun title(key: QuickLaunchKey): String =
        "Unreal ${key.targetType} ${key.platform}"

    companion object {
        internal fun createForTest(processFactory: QuickLaunchProcessFactory): QuickLaunchProcessService =
            QuickLaunchProcessService(processFactory)
    }
}

internal interface QuickLaunchProcessFactory {
    fun create(commandLine: GeneralCommandLine, title: String): QuickLaunchProcess
}

internal interface QuickLaunchProcess {
    val isProcessTerminated: Boolean

    fun destroy()

    fun addTerminationListener(listener: () -> Unit)

    fun run()
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
    override val isProcessTerminated: Boolean
        get() = handler.isProcessTerminated

    override fun destroy() {
        handler.destroyProcess()
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
            .withActivateToolWindow(true)
            .withFocusToolWindow(true)
            .run()
    }
}
