package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.command.UnrealCommand
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.util.Key
import com.intellij.execution.RunContentExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import javax.swing.SwingUtilities

interface UnrealWorkflowProcessListener {
    fun started()

    fun output(text: String, outputType: ProcessOutputType)

    fun terminated(exitCode: Int)

    fun failedToStart(exception: RuntimeException) = Unit
}

interface UnrealWorkflowProcess {
    val commandDescription: String?
        get() = null
    val isProcessTerminating: Boolean
    val isProcessTerminated: Boolean

    fun start(listener: UnrealWorkflowProcessListener)

    fun destroy()
}

object UnrealWorkflowProcessFactory {
    fun create(command: UnrealCommand): UnrealWorkflowProcess =
        RiderUnrealWorkflowProcess(
            OSProcessHandler(
                GeneralCommandLine(command.executable)
                    .withParameters(command.arguments)
                    .withWorkDirectory(command.workingDirectory),
            ),
            commandDescription = buildString {
                append(command.executable)
                command.arguments.forEach { append(' ').append(it) }
            },
        )

    fun createLaunch(project: Project, commandLine: GeneralCommandLine, title: String): UnrealWorkflowProcess =
        RiderRunUnrealWorkflowProcess(project, OSProcessHandler(commandLine), title)
}

private class RiderUnrealWorkflowProcess(
    private val handler: OSProcessHandler,
    override val commandDescription: String? = null,
) : UnrealWorkflowProcess {
    override val isProcessTerminating: Boolean
        get() = handler.isProcessTerminating

    override val isProcessTerminated: Boolean
        get() = handler.isProcessTerminated

    override fun start(listener: UnrealWorkflowProcessListener) {
        handler.addProcessListener(
            object : ProcessListener {
                override fun startNotified(event: ProcessEvent) {
                    listener.started()
                }

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    listener.output(event.text, ProcessOutputType.fromKey(outputType))
                }

                override fun processTerminated(event: ProcessEvent) {
                    listener.terminated(event.exitCode)
                }
            },
        )
        handler.startNotify()
    }

    override fun destroy() {
        if (!handler.isProcessTerminated && !handler.isProcessTerminating) {
            handler.destroyProcess()
        }
    }
}

private class RiderRunUnrealWorkflowProcess(
    private val project: Project,
    private val handler: OSProcessHandler,
    private val title: String,
) : UnrealWorkflowProcess {
    override val commandDescription: String = handler.commandLine
    override val isProcessTerminating: Boolean
        get() = handler.isProcessTerminating

    override val isProcessTerminated: Boolean
        get() = handler.isProcessTerminated

    override fun start(listener: UnrealWorkflowProcessListener) {
        handler.addProcessListener(listener.asProcessListener())
        runOnEdt {
            if (handler.isProcessTerminated || handler.isProcessTerminating) return@runOnEdt
            try {
                RunContentExecutor(project, handler)
                    .withTitle(title)
                    .withStop(Runnable(::destroy), Computable(::canStop))
                    .withActivateToolWindow(true)
                    .withFocusToolWindow(true)
                    .run()
            } catch (exception: RuntimeException) {
                listener.failedToStart(exception)
                destroy()
            }
        }
    }

    override fun destroy() {
        if (!handler.isStartNotified) handler.startNotify()
        if (canStop()) handler.destroyProcess()
    }

    private fun canStop(): Boolean = !handler.isProcessTerminated && !handler.isProcessTerminating
}

private fun UnrealWorkflowProcessListener.asProcessListener(): ProcessListener =
    object : ProcessListener {
        override fun startNotified(event: ProcessEvent) {
            started()
        }

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            output(event.text, ProcessOutputType.fromKey(outputType))
        }

        override fun processTerminated(event: ProcessEvent) {
            terminated(event.exitCode)
        }
    }

internal fun runOnEdt(task: () -> Unit) {
    val application = ApplicationManager.getApplication()
    if (application == null) {
        if (SwingUtilities.isEventDispatchThread()) task() else SwingUtilities.invokeLater(task)
    } else if (application.isDispatchThread) {
        task()
    } else {
        application.invokeLater(task)
    }
}
