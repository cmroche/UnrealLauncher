package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.command.UnrealCommand
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.util.Key

interface UnrealWorkflowProcessListener {
    fun started()

    fun output(text: String, outputType: ProcessOutputType)

    fun terminated(exitCode: Int)
}

interface UnrealWorkflowProcess {
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
        )
}

private class RiderUnrealWorkflowProcess(
    private val handler: OSProcessHandler,
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
