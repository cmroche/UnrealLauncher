package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.command.UnrealCommand
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.util.Key
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.util.execution.ParametersListUtil
import com.jetbrains.rider.run.configurations.TerminalMode
import com.jetbrains.rider.run.configurations.exe.ExeConfiguration
import com.jetbrains.rider.run.configurations.exe.ExeConfigurationParameters
import com.jetbrains.rider.run.configurations.exe.ExeConfigurationType
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

    fun createDebugLaunch(project: Project, commandLine: GeneralCommandLine, title: String): UnrealWorkflowProcess =
        RiderDebugUnrealWorkflowProcess(project, commandLine, title)
}

internal fun debugConfigurationParameters(commandLine: GeneralCommandLine): ExeConfigurationParameters =
    ExeConfigurationParameters(
        exePath = commandLine.exePath,
        programParameters = ParametersListUtil.join(commandLine.parametersList.list),
        workingDirectory = commandLine.workingDirectory?.toString().orEmpty(),
        envs = commandLine.environment.toMap(),
        isPassParentEnvs = commandLine.isPassParentEnvironment,
        terminalMode = TerminalMode.Auto,
        envFilePaths = emptyList(),
        redirectInputPath = "",
        mixedModeDebugging = false,
    )

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

private class RiderDebugUnrealWorkflowProcess(
    private val project: Project,
    private val commandLine: GeneralCommandLine,
    private val title: String,
) : UnrealWorkflowProcess {
    override val commandDescription: String = commandLine.commandLineString

    @Volatile
    private var handler: ProcessHandler? = null

    @Volatile
    private var startNotified = false

    @Volatile
    private var destroyRequested = false

    @Volatile
    private var terminated = false

    override val isProcessTerminating: Boolean
        get() = handler?.isProcessTerminating == true || destroyRequested && !isProcessTerminated

    override val isProcessTerminated: Boolean
        get() = terminated || handler?.isProcessTerminated == true

    override fun start(listener: UnrealWorkflowProcessListener) {
        runOnEdt {
            if (terminated) return@runOnEdt
            try {
                val settings = RunManager.getInstance(project).createConfiguration(
                    title,
                    ExeConfigurationType::class.java,
                ).also {
                    it.isTemporary = true
                    it.configuration.isAllowRunningInParallel = true
                }
                (settings.configuration as ExeConfiguration).parameters.apply {
                    val debugParameters = debugConfigurationParameters(commandLine)
                    exePath = debugParameters.exePath
                    programParameters = debugParameters.programParameters
                    workingDirectory = debugParameters.workingDirectory
                    envs = debugParameters.envs
                    isPassParentEnvs = debugParameters.isPassParentEnvs
                    terminalMode = debugParameters.terminalMode
                }
                val environment = ExecutionEnvironmentBuilder.create(
                    DefaultDebugExecutor.getDebugExecutorInstance(),
                    settings,
                ).build()
                val connection = project.messageBus.connect()
                connection.subscribe(
                    ExecutionManager.EXECUTION_TOPIC,
                    object : ExecutionListener {
                        override fun processStarting(
                            executorId: String,
                            eventEnvironment: ExecutionEnvironment,
                            processHandler: ProcessHandler,
                        ) {
                            if (eventEnvironment !== environment) return
                            attach(processHandler, listener) { connection.disconnect() }
                        }

                        override fun processStarted(
                            executorId: String,
                            eventEnvironment: ExecutionEnvironment,
                            processHandler: ProcessHandler,
                        ) {
                            if (eventEnvironment !== environment) return
                            attach(processHandler, listener) { connection.disconnect() }
                            notifyStarted(listener)
                        }

                        override fun processNotStarted(
                            executorId: String,
                            eventEnvironment: ExecutionEnvironment,
                            cause: Throwable,
                        ) {
                            failToStart(environment, eventEnvironment, listener, connection::disconnect, cause)
                        }

                        override fun processNotStarted(
                            executorId: String,
                            eventEnvironment: ExecutionEnvironment,
                        ) {
                            failToStart(environment, eventEnvironment, listener, connection::disconnect)
                        }
                    },
                )
                try {
                    environment.runner.execute(environment)
                } catch (exception: Exception) {
                    connection.disconnect()
                    throw exception
                }
            } catch (exception: RuntimeException) {
                notifyFailedToStart(listener, exception)
            } catch (exception: Exception) {
                notifyFailedToStart(listener, RuntimeException("Could not start Rider debugger", exception))
            }
        }
    }

    override fun destroy() {
        destroyRequested = true
        handler?.let { processHandler ->
            if (!processHandler.isProcessTerminated && !processHandler.isProcessTerminating) {
                processHandler.destroyProcess()
            }
        }
    }

    private fun attach(
        processHandler: ProcessHandler,
        listener: UnrealWorkflowProcessListener,
        disconnected: () -> Unit,
    ) {
        if (handler != null) return
        handler = processHandler
        processHandler.addProcessListener(
            object : ProcessListener {
                override fun startNotified(event: ProcessEvent) {
                    notifyStarted(listener)
                }

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    listener.output(event.text, ProcessOutputType.fromKey(outputType))
                }

                override fun processTerminated(event: ProcessEvent) {
                    terminated = true
                    disconnected()
                    listener.terminated(event.exitCode)
                }
            },
        )
        if (processHandler.isStartNotified) notifyStarted(listener)
        if (destroyRequested && !processHandler.isProcessTerminated && !processHandler.isProcessTerminating) {
            processHandler.destroyProcess()
        }
    }

    private fun notifyStarted(listener: UnrealWorkflowProcessListener) {
        if (startNotified) return
        startNotified = true
        listener.started()
    }

    private fun failToStart(
        expectedEnvironment: ExecutionEnvironment,
        eventEnvironment: ExecutionEnvironment,
        listener: UnrealWorkflowProcessListener,
        disconnected: () -> Unit,
        cause: Throwable? = null,
    ) {
        if (eventEnvironment !== expectedEnvironment || terminated) return
        disconnected()
        notifyFailedToStart(listener, RuntimeException("Could not start Rider debugger", cause))
    }

    private fun notifyFailedToStart(listener: UnrealWorkflowProcessListener, exception: RuntimeException) {
        if (terminated) return
        terminated = true
        listener.failedToStart(exception)
    }
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
