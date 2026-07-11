package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.launch.QuickLaunchKey
import com.cmroche.unrealhelper.launch.QuickLaunchProcess
import com.cmroche.unrealhelper.launch.QuickLaunchProcessFactory
import com.cmroche.unrealhelper.launch.QuickLaunchProcessService
import com.cmroche.unrealhelper.workflow.BuildBatch
import com.cmroche.unrealhelper.workflow.Cook
import com.cmroche.unrealhelper.workflow.Launch
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import com.cmroche.unrealhelper.workflow.UnrealCookMode
import com.cmroche.unrealhelper.workflow.UnrealExecutionPlan
import com.cmroche.unrealhelper.workflow.UnrealPlanPhase
import com.cmroche.unrealhelper.workflow.UnrealPlannedAction
import com.cmroche.unrealhelper.workflow.UnrealWorkflowRequest
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class UnrealWorkflowExecutionServiceTest {
    @Test
    fun `any active queue conflicts even when artifacts do not intersect`() {
        val fixture = fixture()
        val runningAction = Cook(clientArtifact, UnrealCookMode.FULL)
        fixture.queue.start(plan(UnrealWorkflowRequest.COOK, runningAction))

        val conflict = fixture.service.conflictFor(plan(UnrealWorkflowRequest.BUILD, BuildBatch(setOf(serverArtifact))))

        assertEquals(listOf("Cook LyraClient [Client, Win64, Development] (full)"), conflict?.runningActions)
        assertEquals(emptyList<String>(), conflict?.queuedActions)
        assertEquals(emptyList<Any>(), conflict?.launchedProcesses)
    }

    @Test
    fun `idle queue conflicts only with launches for intersecting artifacts`() {
        val fixture = fixture()
        fixture.launch(clientKey, clientArtifact)
        fixture.launch(serverKey, serverArtifact)

        val conflict = fixture.service.conflictFor(plan(UnrealWorkflowRequest.BUILD, BuildBatch(setOf(clientArtifact))))

        assertEquals(listOf(clientKey), conflict?.launchedProcesses?.map { it.key })
        assertNull(
            fixture.service.conflictFor(
                plan(UnrealWorkflowRequest.BUILD, BuildBatch(setOf(editorArtifact))),
            ),
        )
    }

    @Test
    fun `keeping running work does not submit the conflicting plan`() {
        val fixture = fixture()
        fixture.launch(clientKey, clientArtifact)
        val replacement = plan(UnrealWorkflowRequest.BUILD, BuildBatch(setOf(clientArtifact)))

        assertTrue(fixture.service.conflictFor(replacement) != null)

        assertEquals(emptyList<UnrealPlannedAction>(), fixture.executor.createdActions)
        assertEquals(UnrealPlanState.IDLE, fixture.queue.snapshot().state)
    }

    @Test
    fun `start submits a nonconflicting plan`() {
        val fixture = fixture()
        val action = BuildBatch(setOf(editorArtifact))

        fixture.service.start(plan(UnrealWorkflowRequest.BUILD, action))

        assertEquals(listOf(action), fixture.executor.createdActions)
    }

    @Test
    fun `started workflow launches are registered with exact row and artifact metadata until termination`() {
        val fixture = fixture()
        val launch = Launch(clientArtifact, "Development", 3, "-log", "")
        fixture.service.start(plan(UnrealWorkflowRequest.LAUNCH, launch))

        fixture.executor.current.startSuccessfully()

        val running = fixture.launchService.runningLaunches().single()
        assertEquals(QuickLaunchKey("Development", 3, "LyraClient", "Client", "Win64"), running.key)
        assertEquals(clientArtifact, running.artifact)
        assertEquals("Unreal Development 4: LyraClient Client Win64", running.title)

        fixture.executor.current.terminate(0)

        assertEquals(emptyList<Any>(), fixture.launchService.runningLaunches())
    }

    @Test
    fun `stop and restart waits for a tracked workflow launch to terminate`() {
        val fixture = fixture()
        val launch = Launch(clientArtifact, "Development", 0, "", "")
        fixture.service.start(plan(UnrealWorkflowRequest.LAUNCH, launch))
        val launchedProcess = fixture.executor.current
        launchedProcess.startSuccessfully()
        val replacementAction = BuildBatch(setOf(clientArtifact))
        val replacement = plan(UnrealWorkflowRequest.BUILD, replacementAction)

        fixture.service.stopAndRestart(replacement, fixture.service.conflictFor(replacement)!!)

        assertTrue(launchedProcess.destroyCalled)
        assertEquals(listOf(launch), fixture.executor.createdActions)

        launchedProcess.terminate(143)

        assertEquals(listOf(launch, replacementAction), fixture.executor.createdActions)
    }

    @Test
    fun `stop and restart waits for queue and selected launches and preserves nonconflicting launches`() {
        val fixture = fixture()
        val currentAction = Cook(clientArtifact, UnrealCookMode.FULL)
        fixture.queue.start(plan(UnrealWorkflowRequest.COOK, currentAction))
        fixture.executor.current.startSuccessfully()
        val conflictingLaunch = fixture.launch(clientKey, clientArtifact)
        val preservedLaunch = fixture.launch(serverKey, serverArtifact)
        val replacementAction = BuildBatch(setOf(clientArtifact))
        val replacement = plan(UnrealWorkflowRequest.BUILD, replacementAction)
        val conflict = fixture.service.conflictFor(replacement)!!

        fixture.service.stopAndRestart(replacement, conflict)

        assertTrue(fixture.executor.processes.single().destroyCalled)
        assertTrue(conflictingLaunch.destroyed)
        assertFalse(preservedLaunch.destroyed)
        assertEquals(listOf(currentAction), fixture.executor.createdActions)

        fixture.executor.processes.single().terminate(143)
        assertEquals(listOf(currentAction), fixture.executor.createdActions)

        conflictingLaunch.terminate()
        assertEquals(listOf(currentAction, replacementAction), fixture.executor.createdActions)
        assertEquals(setOf(serverKey), fixture.launchService.runningKeys())

        conflictingLaunch.terminate()
        assertEquals(listOf(currentAction, replacementAction), fixture.executor.createdActions)
    }

    private fun fixture(): Fixture {
        val executor = FakeExecutor()
        val queue = UnrealExecutionQueue(executor, { NoOpPresenter() })
        val launchFactory = FakeLaunchFactory()
        val launchService = QuickLaunchProcessService.createForTest(launchFactory)
        return Fixture(
            service = UnrealWorkflowExecutionService(queue, launchService),
            queue = queue,
            executor = executor,
            launchService = launchService,
            launchFactory = launchFactory,
        )
    }

    private fun plan(request: UnrealWorkflowRequest, action: UnrealPlannedAction): UnrealExecutionPlan =
        UnrealExecutionPlan(
            request = request,
            configurationName = "Development",
            globalArguments = "",
            phases = listOf(UnrealPlanPhase(action.phase, listOf(action))),
        )

    private data class Fixture(
        val service: UnrealWorkflowExecutionService,
        val queue: UnrealExecutionQueue,
        val executor: FakeExecutor,
        val launchService: QuickLaunchProcessService,
        val launchFactory: FakeLaunchFactory,
    ) {
        fun launch(key: QuickLaunchKey, artifact: UnrealArtifactKey): FakeLaunchProcess {
            launchService.launch(key, artifact, GeneralCommandLine("/tmp/Lyra"))
            return launchFactory.processes.last()
        }
    }

    private class FakeExecutor : UnrealPlannedActionExecutor {
        val createdActions = mutableListOf<UnrealPlannedAction>()
        val processes = mutableListOf<FakeWorkflowProcess>()
        val current: FakeWorkflowProcess get() = processes.last()

        override fun create(action: UnrealPlannedAction): UnrealWorkflowProcess =
            FakeWorkflowProcess().also {
                createdActions += action
                processes += it
            }
    }

    private class FakeWorkflowProcess : UnrealWorkflowProcess {
        private lateinit var listener: UnrealWorkflowProcessListener
        override var isProcessTerminating: Boolean = false
        override var isProcessTerminated: Boolean = false
        var destroyCalled: Boolean = false

        override fun start(listener: UnrealWorkflowProcessListener) {
            this.listener = listener
        }

        override fun destroy() {
            destroyCalled = true
            isProcessTerminating = true
        }

        fun startSuccessfully() = listener.started()

        fun terminate(exitCode: Int) {
            isProcessTerminating = false
            isProcessTerminated = true
            listener.terminated(exitCode)
        }
    }

    private class FakeLaunchFactory : QuickLaunchProcessFactory {
        val processes = mutableListOf<FakeLaunchProcess>()

        override fun create(commandLine: GeneralCommandLine, title: String): QuickLaunchProcess =
            FakeLaunchProcess().also(processes::add)
    }

    private class FakeLaunchProcess : QuickLaunchProcess {
        private val listeners = mutableListOf<() -> Unit>()
        private var terminated = false
        var destroyed = false

        override val isProcessTerminated: Boolean get() = terminated

        override fun destroy() {
            destroyed = true
        }

        override fun addTerminationListener(listener: () -> Unit) {
            listeners += listener
        }

        override fun run() = Unit

        fun terminate() {
            terminated = true
            listeners.toList().forEach { it() }
        }
    }

    private class NoOpPresenter : UnrealWorkflowPresenter {
        override fun start(plan: UnrealExecutionPlan) = Unit
        override fun actionStarted(action: UnrealPlannedAction) = Unit
        override fun output(action: UnrealPlannedAction, text: String, type: ProcessOutputType) = Unit
        override fun actionFinished(action: UnrealPlannedAction, result: UnrealActionResult) = Unit
        override fun finish(result: UnrealPlanResult) = Unit
    }

    companion object {
        private val project = Path.of("/project/Lyra.uproject")
        private val clientArtifact = artifact("LyraClient", "Client")
        private val serverArtifact = artifact("LyraServer", "Server")
        private val editorArtifact = artifact("LyraEditor", "Editor")
        private val clientKey = QuickLaunchKey("Development", 0, "LyraClient", "Client", "Win64")
        private val serverKey = QuickLaunchKey("Development", 1, "LyraServer", "Server", "Win64")

        private fun artifact(name: String, type: String) = UnrealArtifactKey(
            projectPath = project,
            targetName = name,
            targetType = type,
            platform = "Win64",
            buildConfiguration = "Development",
        )
    }
}
