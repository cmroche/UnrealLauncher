package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.workflow.BuildBatch
import com.cmroche.unrealhelper.workflow.Cook
import com.cmroche.unrealhelper.workflow.Launch
import com.cmroche.unrealhelper.workflow.Package
import com.cmroche.unrealhelper.workflow.Stage
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import com.cmroche.unrealhelper.workflow.UnrealCookMode
import com.cmroche.unrealhelper.workflow.UnrealExecutionPlan
import com.cmroche.unrealhelper.workflow.UnrealPlanPhase
import com.cmroche.unrealhelper.workflow.UnrealPlannedAction
import com.cmroche.unrealhelper.workflow.UnrealWorkflowRequest
import com.intellij.execution.process.ProcessOutputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class UnrealExecutionQueueTest {
    @Test
    fun `actions execute in strict phase order one process at a time`() {
        val actions = listOf(
            BuildBatch(setOf(artifact)),
            Cook(artifact, UnrealCookMode.FULL),
            Stage(artifact),
            Package(artifact),
        )
        val fixture = fixture()

        fixture.queue.start(plan(UnrealWorkflowRequest.PACKAGE, actions))

        assertEquals(listOf(actions[0]), fixture.executor.createdActions)
        assertEquals(actions[0], fixture.queue.snapshot().running)
        actions.indices.forEach { index ->
            fixture.executor.process(index).startSuccessfully()
            assertEquals(index + 1, fixture.executor.createdActions.size)
            fixture.executor.process(index).terminate(0)
            assertEquals(actions.take(index + 2), fixture.executor.createdActions)
        }
        assertEquals(UnrealPlanState.SUCCEEDED, fixture.queue.snapshot().state)
        assertNull(fixture.queue.snapshot().running)
    }

    @Test
    fun `nonzero exit clears queued actions and prevents launch`() {
        val cook = Cook(artifact, UnrealCookMode.FULL)
        val launch = launch(rowIndex = 0)
        val fixture = fixture()

        fixture.queue.start(plan(UnrealWorkflowRequest.LAUNCH, listOf(cook, launch)))
        fixture.executor.current.startSuccessfully()
        fixture.executor.current.terminate(7)

        val snapshot = fixture.queue.snapshot()
        assertEquals(UnrealPlanState.FAILED, snapshot.state)
        assertEquals(emptyList<UnrealPlannedAction>(), snapshot.queued)
        assertEquals(listOf(cook), fixture.executor.createdActions)
        assertEquals(UnrealPlanResult.Failure(cook, 7), fixture.presenters.single().finished.single())
    }

    @Test
    fun `launch completes on startup and subsequent launches start without waiting for exit`() {
        val first = launch(rowIndex = 0)
        val second = launch(rowIndex = 1)
        val fixture = fixture()

        fixture.queue.start(plan(UnrealWorkflowRequest.LAUNCH, listOf(first, second)))
        val firstProcess = fixture.executor.current
        firstProcess.startSuccessfully()

        assertEquals(listOf(first, second), fixture.executor.createdActions)
        assertEquals(listOf(first), fixture.callbacks.startedActions)
        assertFalse(firstProcess.destroyCalled)

        val secondProcess = fixture.executor.current
        secondProcess.startSuccessfully()
        assertEquals(UnrealPlanState.SUCCEEDED, fixture.queue.snapshot().state)
        assertEquals(listOf(first, second), fixture.callbacks.startedActions)

        firstProcess.terminate(13)
        assertEquals(UnrealPlanState.SUCCEEDED, fixture.queue.snapshot().state)
        assertEquals(listOf(first to 13), fixture.callbacks.terminatedActions)
    }

    @Test
    fun `ordinary failure does not destroy an already started launch`() {
        val launched = launch(rowIndex = 0)
        val fixture = fixture()
        fixture.queue.start(plan(UnrealWorkflowRequest.LAUNCH, listOf(launched)))
        val launchedProcess = fixture.executor.current
        launchedProcess.startSuccessfully()

        fixture.queue.start(plan(UnrealWorkflowRequest.COOK, listOf(Cook(artifact, UnrealCookMode.FULL))))
        val cookProcess = fixture.executor.current
        cookProcess.startSuccessfully()
        cookProcess.terminate(1)

        assertFalse(launchedProcess.destroyCalled)
        assertEquals(listOf(launched), fixture.callbacks.startedActions)
    }

    @Test
    fun `snapshot exposes typed actions and display names`() {
        val build = BuildBatch(setOf(artifact))
        val cook = Cook(artifact, UnrealCookMode.INCREMENTAL)
        val fixture = fixture()

        fixture.queue.start(plan(UnrealWorkflowRequest.LAUNCH, listOf(build, cook)))

        val snapshot = fixture.queue.snapshot()
        assertTrue(snapshot.isActive)
        assertEquals(build, snapshot.running)
        assertEquals(listOf(cook), snapshot.queued)
        assertEquals(listOf("Build LyraClient [Client, Win64, Development]"), snapshot.runningNames)
        assertEquals(listOf("Cook LyraClient [Client, Win64, Development] (incremental)"), snapshot.queuedNames)
    }

    @Test
    fun `replacement waits for current termination then starts latest pending plan`() {
        val fixture = fixture()
        val current = Cook(artifact, UnrealCookMode.FULL)
        val discarded = Stage(artifact)
        val firstReplacement = plan(UnrealWorkflowRequest.BUILD, listOf(BuildBatch(setOf(artifact))))
        val latestReplacement = plan(UnrealWorkflowRequest.PACKAGE, listOf(Package(artifact)))
        fixture.queue.start(plan(UnrealWorkflowRequest.PACKAGE, listOf(current, discarded)))
        val currentProcess = fixture.executor.current
        currentProcess.startSuccessfully()

        fixture.queue.stopForReplacement(firstReplacement)
        fixture.queue.stopForReplacement(latestReplacement)

        assertEquals(UnrealPlanState.STOPPING, fixture.queue.snapshot().state)
        assertEquals(emptyList<UnrealPlannedAction>(), fixture.queue.snapshot().queued)
        assertTrue(currentProcess.destroyCalled)
        assertEquals(listOf(current), fixture.executor.createdActions)

        currentProcess.terminate(143)

        assertEquals(listOf(current, latestReplacement.phases.single().actions.single()), fixture.executor.createdActions)
        assertEquals(UnrealPlanResult.Cancelled, fixture.presenters.first().finished.single())
        assertEquals(UnrealPlanState.RUNNING, fixture.queue.snapshot().state)
    }

    @Test
    fun `replacement waits when termination flags lag after destroy`() {
        val fixture = fixture()
        val currentPlan = plan(UnrealWorkflowRequest.COOK, listOf(Cook(artifact, UnrealCookMode.FULL)))
        val replacement = plan(UnrealWorkflowRequest.BUILD, listOf(BuildBatch(setOf(artifact))))
        fixture.queue.start(currentPlan)
        val process = fixture.executor.current.apply {
            startSuccessfully()
            destroyStartsTermination = false
        }

        fixture.queue.stopForReplacement(replacement)

        assertTrue(process.destroyCalled)
        assertFalse(process.isProcessTerminating)
        assertFalse(process.isProcessTerminated)
        assertEquals(UnrealPlanState.STOPPING, fixture.queue.snapshot().state)
        assertEquals(1, fixture.executor.createdActions.size)

        process.terminate(143)

        assertEquals(
            listOf(currentPlan.phases.single().actions.single(), replacement.phases.single().actions.single()),
            fixture.executor.createdActions,
        )
        assertEquals(UnrealPlanState.RUNNING, fixture.queue.snapshot().state)
    }

    @Test
    fun `replacement is blocked when destroy throws`() {
        val fixture = fixture()
        fixture.queue.start(plan(UnrealWorkflowRequest.COOK, listOf(Cook(artifact, UnrealCookMode.FULL))))
        fixture.executor.current.apply {
            startSuccessfully()
            destroyFailure = IllegalStateException("cannot destroy")
        }

        fixture.queue.stopForReplacement(plan(UnrealWorkflowRequest.BUILD, listOf(BuildBatch(setOf(artifact)))))

        assertEquals(UnrealPlanState.RESTART_BLOCKED, fixture.queue.snapshot().state)
        assertEquals(1, fixture.executor.createdActions.size)
    }

    @Test
    fun `replacement is blocked when termination callback reports process still alive`() {
        val fixture = fixture()
        fixture.queue.start(plan(UnrealWorkflowRequest.COOK, listOf(Cook(artifact, UnrealCookMode.FULL))))
        val process = fixture.executor.current.apply { startSuccessfully() }
        fixture.queue.stopForReplacement(plan(UnrealWorkflowRequest.BUILD, listOf(BuildBatch(setOf(artifact)))))

        process.terminate(exitCode = 143, remainsAlive = true)

        assertEquals(UnrealPlanState.RESTART_BLOCKED, fixture.queue.snapshot().state)
        assertEquals(1, fixture.executor.createdActions.size)
    }

    private fun fixture(): Fixture {
        val executor = FakeExecutor()
        val presenters = mutableListOf<RecordingPresenter>()
        val callbacks = RecordingCallbacks()
        val queue = UnrealExecutionQueue(
            executor = executor,
            presenterFactory = { RecordingPresenter().also(presenters::add) },
            callbacks = callbacks,
        )
        return Fixture(queue, executor, presenters, callbacks)
    }

    private fun plan(request: UnrealWorkflowRequest, actions: List<UnrealPlannedAction>): UnrealExecutionPlan =
        UnrealExecutionPlan(
            request = request,
            configurationName = "Development",
            globalArguments = "",
            phases = actions.groupBy { it.phase }.map { (phase, phaseActions) -> UnrealPlanPhase(phase, phaseActions) },
        )

    private fun launch(rowIndex: Int): Launch = Launch(artifact, "Development", rowIndex, "", "")

    private data class Fixture(
        val queue: UnrealExecutionQueue,
        val executor: FakeExecutor,
        val presenters: List<RecordingPresenter>,
        val callbacks: RecordingCallbacks,
    )

    private class FakeExecutor : UnrealPlannedActionExecutor {
        val createdActions = mutableListOf<UnrealPlannedAction>()
        private val processes = mutableListOf<FakeProcess>()
        val current: FakeProcess get() = processes.last()

        override fun create(action: UnrealPlannedAction): UnrealWorkflowProcess =
            FakeProcess().also {
                createdActions += action
                processes += it
            }

        fun process(index: Int): FakeProcess = processes[index]
    }

    private class FakeProcess : UnrealWorkflowProcess {
        private lateinit var listener: UnrealWorkflowProcessListener
        override var isProcessTerminating = false
        override var isProcessTerminated = false
        var destroyCalled = false
        var destroyStartsTermination = true
        var destroyFailure: RuntimeException? = null

        override fun start(listener: UnrealWorkflowProcessListener) {
            this.listener = listener
        }

        override fun destroy() {
            destroyCalled = true
            destroyFailure?.let { throw it }
            if (destroyStartsTermination) isProcessTerminating = true
        }

        fun startSuccessfully() = listener.started()

        fun terminate(exitCode: Int, remainsAlive: Boolean = false) {
            isProcessTerminating = false
            isProcessTerminated = !remainsAlive
            listener.terminated(exitCode)
        }
    }

    private class RecordingPresenter : UnrealWorkflowPresenter {
        val finished = mutableListOf<UnrealPlanResult>()

        override fun start(plan: UnrealExecutionPlan) = Unit
        override fun actionStarted(action: UnrealPlannedAction) = Unit
        override fun output(action: UnrealPlannedAction, text: String, type: ProcessOutputType) = Unit
        override fun actionFinished(action: UnrealPlannedAction, result: UnrealActionResult) = Unit
        override fun finish(result: UnrealPlanResult) {
            finished += result
        }
    }

    private class RecordingCallbacks : UnrealExecutionQueueCallbacks {
        val startedActions = mutableListOf<Launch>()
        val terminatedActions = mutableListOf<Pair<Launch, Int>>()

        override fun launchStarted(action: Launch, process: UnrealWorkflowProcess) {
            startedActions += action
        }

        override fun launchTerminated(action: Launch, process: UnrealWorkflowProcess, exitCode: Int) {
            terminatedActions += action to exitCode
        }
    }

    companion object {
        private val artifact = UnrealArtifactKey(
            projectPath = Path.of("/project/Lyra.uproject"),
            targetName = "LyraClient",
            targetType = "Client",
            platform = "Win64",
            buildConfiguration = "Development",
        )
    }
}
