package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.launch.QuickLaunchProcessService
import com.cmroche.unrealhelper.launch.QuickLaunchStopResult
import com.cmroche.unrealhelper.launch.QuickLaunchKey
import com.cmroche.unrealhelper.launch.RunningLaunchInfo
import com.cmroche.unrealhelper.workflow.Launch
import com.cmroche.unrealhelper.workflow.UnrealExecutionPlan
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.cmroche.unrealhelper.actions.UnrealActionMessages

data class UnrealWorkflowConflict(
    val runningActions: List<String>,
    val queuedActions: List<String>,
    val launchedProcesses: List<RunningLaunchInfo>,
)

interface UnrealWorkflowExecution {
    fun conflictFor(plan: UnrealExecutionPlan): UnrealWorkflowConflict?

    fun start(plan: UnrealExecutionPlan)

    fun stopAndRestart(plan: UnrealExecutionPlan, conflict: UnrealWorkflowConflict)
}

@Service(Service.Level.PROJECT)
class UnrealWorkflowExecutionService private constructor(
    private val queue: UnrealExecutionQueue,
    private val launchService: QuickLaunchProcessService,
    private val failureReporter: (String) -> Unit,
) : UnrealExecutionQueueCallbacks, UnrealWorkflowExecution {
    constructor(project: Project) : this(
        queue = UnrealExecutionQueue(
            executor = RiderUnrealPlannedActionExecutor(project),
            presenterFactory = { UnrealWorkflowPresenter.create(project) },
        ),
        launchService = project.service<QuickLaunchProcessService>(),
        failureReporter = { message ->
            ApplicationManager.getApplication().invokeLater {
                UnrealActionMessages.showError(project, message)
            }
        },
    )

    init {
        queue.setCallbacks(this)
    }

    override fun conflictFor(plan: UnrealExecutionPlan): UnrealWorkflowConflict? {
        val snapshot = queue.snapshot()
        val planArtifacts = plan.phases
            .flatMap { it.actions }
            .flatMapTo(mutableSetOf()) { it.artifacts }
        val planLaunchKeys = plan.phases
            .flatMap { it.actions }
            .filterIsInstance<Launch>()
            .mapTo(mutableSetOf()) { it.quickLaunchKey() }
        val conflictingLaunches = launchService.runningLaunches().filter {
            it.artifact in planArtifacts || it.key in planLaunchKeys
        }

        return UnrealWorkflowConflict(
            runningActions = snapshot.runningNames,
            queuedActions = snapshot.queuedNames,
            launchedProcesses = conflictingLaunches,
        ).takeIf { snapshot.isActive || conflictingLaunches.isNotEmpty() }
    }

    override fun start(plan: UnrealExecutionPlan) {
        queue.start(plan)
    }

    override fun stopAndRestart(plan: UnrealExecutionPlan, conflict: UnrealWorkflowConflict) {
        val barrier = CompletionBarrier(
            parties = 2,
            completed = { queue.start(plan) },
            failed = queue::blockRestart,
        )
        queue.stopAndWait { barrier.arrive(QuickLaunchStopResult.Completed) }
        launchService.stopAndWait(conflict.launchedProcesses, barrier::arrive, queue::recoverBlockedRestart)
    }

    override fun launchStarted(action: Launch, process: UnrealWorkflowProcess) {
        val key = action.quickLaunchKey()
        launchService.registerRunningLaunch(key, action.artifact, title(key), process)
    }

    override fun launchOutput(
        action: Launch,
        process: UnrealWorkflowProcess,
        text: String,
        outputType: ProcessOutputType,
    ) = Unit

    override fun launchTerminated(action: Launch, process: UnrealWorkflowProcess, exitCode: Int) {
        launchService.runningLaunchTerminated(process)
    }

    override fun workflowFailed(result: UnrealPlanResult.Failure) {
        failureReporter(buildString {
            append("Unreal workflow failed: ").append(result.action.displayName())
            result.detail?.let { append("\n").append(it) }
            result.command?.let { append("\nCommand: ").append(it) }
            if (result.cancelledActions.isNotEmpty()) {
                append("\nCancelled: ")
                append(result.cancelledActions.joinToString { it.displayName() })
            }
        })
    }

    override fun restartFailed(message: String) {
        failureReporter("Unreal workflow restart failed: $message. The replacement was not started.")
    }

    private fun Launch.quickLaunchKey(): QuickLaunchKey =
        QuickLaunchKey(
            configurationName = configurationName,
            entryIndex = rowIndex,
            targetName = artifact.targetName,
            targetType = artifact.targetType,
            platform = artifact.platform,
        )

    private fun title(key: QuickLaunchKey): String =
        "Unreal ${key.configurationName} ${key.entryIndex + 1}: ${key.targetName} ${key.targetType} ${key.platform}"

    private class CompletionBarrier(
        private var parties: Int,
        private val completed: () -> Unit,
        private val failed: (String) -> Unit,
    ) {
        private val lock = Any()
        private var resultLatched = false

        fun arrive(result: QuickLaunchStopResult) {
            val completion = synchronized(lock) {
                if (resultLatched || parties == 0) return@synchronized null
                when (result) {
                    is QuickLaunchStopResult.Failed -> {
                        resultLatched = true
                        { failed(result.cause.message ?: result.cause::class.simpleName.orEmpty()) }
                    }
                    QuickLaunchStopResult.Completed -> {
                        parties--
                        if (parties == 0) {
                            resultLatched = true
                            completed
                        } else {
                            null
                        }
                    }
                }
            }
            completion?.invoke()
        }
    }

    companion object {
        internal fun createForTest(
            queue: UnrealExecutionQueue,
            launchService: QuickLaunchProcessService,
            failureReporter: (String) -> Unit = {},
        ): UnrealWorkflowExecutionService = UnrealWorkflowExecutionService(queue, launchService, failureReporter)
    }
}
