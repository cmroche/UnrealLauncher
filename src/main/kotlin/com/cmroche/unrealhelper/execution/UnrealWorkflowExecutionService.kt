package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.launch.QuickLaunchProcessService
import com.cmroche.unrealhelper.launch.QuickLaunchKey
import com.cmroche.unrealhelper.launch.RunningLaunchInfo
import com.cmroche.unrealhelper.workflow.Launch
import com.cmroche.unrealhelper.workflow.UnrealExecutionPlan
import com.intellij.execution.process.ProcessOutputType

data class UnrealWorkflowConflict(
    val runningActions: List<String>,
    val queuedActions: List<String>,
    val launchedProcesses: List<RunningLaunchInfo>,
)

class UnrealWorkflowExecutionService(
    private val queue: UnrealExecutionQueue,
    private val launchService: QuickLaunchProcessService,
) : UnrealExecutionQueueCallbacks {
    init {
        queue.setCallbacks(this)
    }

    fun conflictFor(plan: UnrealExecutionPlan): UnrealWorkflowConflict? {
        val snapshot = queue.snapshot()
        val planArtifacts = plan.phases
            .flatMap { it.actions }
            .flatMapTo(mutableSetOf()) { it.artifacts }
        val conflictingLaunches = launchService.runningLaunches().filter { it.artifact in planArtifacts }

        return UnrealWorkflowConflict(
            runningActions = snapshot.runningNames,
            queuedActions = snapshot.queuedNames,
            launchedProcesses = conflictingLaunches,
        ).takeIf { snapshot.isActive || conflictingLaunches.isNotEmpty() }
    }

    fun start(plan: UnrealExecutionPlan) {
        queue.start(plan)
    }

    fun stopAndRestart(plan: UnrealExecutionPlan, conflict: UnrealWorkflowConflict) {
        val barrier = CompletionBarrier(parties = 2) { queue.start(plan) }
        queue.stopAndWait(barrier::arrive)
        launchService.stopAndWait(conflict.launchedProcesses.mapTo(mutableSetOf()) { it.key }, barrier::arrive)
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
        launchService.runningLaunchTerminated(action.quickLaunchKey(), process)
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
    ) {
        private val lock = Any()
        private var didComplete = false

        fun arrive() {
            val shouldComplete = synchronized(lock) {
                if (didComplete || parties == 0) return@synchronized false
                parties--
                if (parties == 0) {
                    didComplete = true
                    true
                } else {
                    false
                }
            }
            if (shouldComplete) completed()
        }
    }
}
