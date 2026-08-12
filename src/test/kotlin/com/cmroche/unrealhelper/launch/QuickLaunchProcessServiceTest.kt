package com.cmroche.unrealhelper.launch

import com.cmroche.unrealhelper.execution.UnrealRestartTimeoutScheduler
import com.cmroche.unrealhelper.execution.UnrealWorkflowProcess
import com.cmroche.unrealhelper.execution.UnrealWorkflowProcessListener
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class QuickLaunchProcessServiceTest {
    @Test
    fun `same row workflow launches remain independently visible and stoppable`() {
        val service = QuickLaunchProcessService.createForTest()
        val key = key(targetName = "LyraClient", targetType = "Client")
        val first = register(service, key, artifact("LyraClient", "Client"), "first")
        val second = register(service, key, artifact("LyraClient", "Client"), "second")

        assertEquals(2, service.runningLaunches().size)
        service.stop(key)
        assertTrue(first.destroyed)
        assertTrue(second.destroyed)

        first.terminate(service)

        assertEquals(listOf("second"), service.runningLaunches().map { it.title })
    }

    @Test
    fun `stop destroys a tracked process until workflow termination removes it`() {
        val service = QuickLaunchProcessService.createForTest()
        val key = key(targetName = "LyraGame", targetType = "Game")
        val process = register(service, key, artifact("LyraGame", "Game"))

        service.stop(key)

        assertTrue(process.destroyed)
        assertTrue(service.isRunning(key))
        process.terminate(service)
        assertFalse(service.isRunning(key))
        assertEquals(emptySet<QuickLaunchKey>(), service.runningKeys())
    }

    @Test
    fun `stopAll destroys all tracked processes`() {
        val service = QuickLaunchProcessService.createForTest()
        val game = key(targetName = "LyraGame", targetType = "Game")
        val server = key(entryIndex = 1, targetName = "LyraServer", targetType = "Server", platform = "Linux")
        val gameProcess = register(service, game, artifact("LyraGame", "Game"))
        val serverProcess = register(service, server, artifact("LyraServer", "Server", "Linux"))

        service.stopAll()

        assertTrue(gameProcess.destroyed)
        assertTrue(serverProcess.destroyed)
        gameProcess.terminate(service)
        serverProcess.terminate(service)
        assertEquals(emptySet<QuickLaunchKey>(), service.runningKeys())
    }

    @Test
    fun `dispose stops tracked processes`() {
        val service = QuickLaunchProcessService.createForTest()
        val game = key(targetName = "LyraGame", targetType = "Game")
        val server = key(entryIndex = 1, targetName = "LyraServer", targetType = "Server", platform = "Linux")
        val processes = listOf(
            register(service, game, artifact("LyraGame", "Game")),
            register(service, server, artifact("LyraServer", "Server", "Linux")),
        )

        service.dispose()

        assertTrue(processes.all { it.destroyed })
    }

    @Test
    fun `duplicate target platform entries can run independently`() {
        val service = QuickLaunchProcessService.createForTest()
        val first = QuickLaunchKey("Three Clients", 0, "LyraClient", "Client", "Win64")
        val second = QuickLaunchKey("Three Clients", 1, "LyraClient", "Client", "Win64")
        val artifact = artifact("LyraClient", "Client")

        register(service, first, artifact)
        register(service, second, artifact)

        assertEquals(setOf(first, second), service.runningKeys())
        assertEquals(2, service.runningLaunches().size)
    }

    @Test
    fun `running launches retain exact key artifact and title metadata`() {
        val service = QuickLaunchProcessService.createForTest()
        val key = key(targetName = "LyraClient", targetType = "Client")
        val artifact = artifact("LyraClient", "Client")
        val title = "Unreal Default 1: LyraClient Client Win64"

        register(service, key, artifact, title)

        val running = service.runningLaunches().single()
        assertEquals(key, running.key)
        assertEquals(artifact, running.artifact)
        assertEquals(title, running.title)
    }

    @Test
    fun `stop and wait invokes callback after every selected process terminates`() {
        val service = QuickLaunchProcessService.createForTest()
        val first = key(entryIndex = 0, targetName = "LyraClient", targetType = "Client")
        val second = key(entryIndex = 1, targetName = "LyraClient", targetType = "Client")
        val untouched = key(entryIndex = 2, targetName = "LyraServer", targetType = "Server")
        val firstProcess = register(service, first, artifact("LyraClient", "Client"))
        val secondProcess = register(service, second, artifact("LyraClient", "Client"))
        val untouchedProcess = register(service, untouched, artifact("LyraServer", "Server"))
        val callbacks = mutableListOf<QuickLaunchStopResult>()

        service.stopAndWait(service.runningLaunches().filter { it.key in setOf(first, second) }, callbacks::add)

        assertTrue(firstProcess.destroyed)
        assertTrue(secondProcess.destroyed)
        assertFalse(untouchedProcess.destroyed)
        assertEquals(emptyList<QuickLaunchStopResult>(), callbacks)

        firstProcess.terminate(service)
        assertEquals(emptyList<QuickLaunchStopResult>(), callbacks)

        secondProcess.terminate(service)
        assertEquals(listOf(QuickLaunchStopResult.Completed), callbacks)
        assertEquals(setOf(untouched), service.runningKeys())
    }

    @Test
    fun `stop and wait latches destroy failure despite later natural termination`() {
        val destroyFailure = IllegalStateException("cannot destroy")
        val service = QuickLaunchProcessService.createForTest()
        val key = key(targetName = "LyraClient", targetType = "Client")
        val process = register(
            service,
            key,
            artifact("LyraClient", "Client"),
            destroyFailure = destroyFailure,
        )
        val results = mutableListOf<QuickLaunchStopResult>()
        var recoveries = 0

        service.stopAndWait(service.runningLaunches(), results::add) { recoveries++ }

        assertEquals(listOf(QuickLaunchStopResult.Failed(destroyFailure)), results)

        process.terminate(service)

        assertEquals(listOf(QuickLaunchStopResult.Failed(destroyFailure)), results)
        assertEquals(1, recoveries)
    }

    @Test
    fun `stop and wait has a bounded timeout and recovers after exact termination`() {
        val timeouts = RecordingRestartTimeoutScheduler()
        val service = QuickLaunchProcessService.createForTest(timeouts)
        val key = key(targetName = "LyraClient", targetType = "Client")
        val process = register(service, key, artifact("LyraClient", "Client"))
        val results = mutableListOf<QuickLaunchStopResult>()
        var recoveries = 0

        service.stopAndWait(service.runningLaunches(), results::add) { recoveries++ }
        timeouts.fire()

        assertTrue((results.single() as QuickLaunchStopResult.Failed).cause.message.orEmpty().contains("Timed out"))
        process.terminate(service)
        assertEquals(1, recoveries)
    }

    @Test
    fun `stop and wait preserves newer launch that reused a captured key`() {
        val service = QuickLaunchProcessService.createForTest()
        val key = key(targetName = "LyraClient", targetType = "Client")
        val artifact = artifact("LyraClient", "Client")
        val first = register(service, key, artifact, "first")
        val captured = service.runningLaunches()
        val replacement = register(service, key, artifact, "replacement")
        var result: QuickLaunchStopResult? = null

        service.stopAndWait(captured) { result = it }

        assertEquals(null, result)
        assertFalse(replacement.destroyed)
        assertEquals(2, service.runningLaunches().size)

        first.terminate(service)

        assertEquals(QuickLaunchStopResult.Completed, result)
        assertEquals(listOf("replacement"), service.runningLaunches().map { it.title })
    }

    private fun register(
        service: QuickLaunchProcessService,
        key: QuickLaunchKey,
        artifact: UnrealArtifactKey,
        title: String = "test",
        destroyFailure: RuntimeException? = null,
    ): TestWorkflowProcess = TestWorkflowProcess(destroyFailure).also {
        service.registerRunningLaunch(key, artifact, title, it)
    }

    private fun key(
        entryIndex: Int = 0,
        targetName: String,
        targetType: String,
        platform: String = "Win64",
    ): QuickLaunchKey = QuickLaunchKey("Default", entryIndex, targetName, targetType, platform)

    private fun artifact(
        targetName: String,
        targetType: String,
        platform: String = "Win64",
    ): UnrealArtifactKey = UnrealArtifactKey(
        projectPath = Path.of("/project/Lyra.uproject"),
        targetName = targetName,
        targetType = targetType,
        platform = platform,
        buildConfiguration = "Development",
    )
}

private class RecordingRestartTimeoutScheduler : UnrealRestartTimeoutScheduler {
    private val tasks = mutableListOf<() -> Unit>()

    override fun schedule(task: () -> Unit) {
        tasks += task
    }

    fun fire() {
        tasks.removeFirst().invoke()
    }
}

private class TestWorkflowProcess(
    private val destroyFailure: RuntimeException? = null,
) : UnrealWorkflowProcess {
    override var isProcessTerminating: Boolean = false
    override var isProcessTerminated: Boolean = false
    var destroyed = false
        private set

    override fun start(listener: UnrealWorkflowProcessListener) = Unit

    override fun destroy() {
        destroyed = true
        destroyFailure?.let { throw it }
        isProcessTerminating = true
    }

    fun terminate(service: QuickLaunchProcessService) {
        isProcessTerminating = false
        isProcessTerminated = true
        service.runningLaunchTerminated(this)
    }
}
