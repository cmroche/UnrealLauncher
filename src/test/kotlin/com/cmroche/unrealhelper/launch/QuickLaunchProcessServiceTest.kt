package com.cmroche.unrealhelper.launch

import com.intellij.execution.configurations.GeneralCommandLine
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Path

class QuickLaunchProcessServiceTest {
    @Test
    fun `launch tracks key and executor title`() {
        val factory = FakeQuickLaunchProcessFactory()
        val service = QuickLaunchProcessService.createForTest(factory)
        val key = key(targetName = "LyraGame", targetType = "Game")

        service.launch(key, artifact(targetName = "LyraGame", targetType = "Game"), commandLine())

        assertTrue(service.isRunning(key))
        assertEquals(setOf(key), service.runningKeys())
        assertEquals(listOf("Unreal Default 1: LyraGame Game Win64"), factory.titles)
        assertEquals(1, factory.processes.size)
        assertTrue(factory.processes.single().started)
    }

    @Test
    fun `relaunching same key stops previous handler and replaces it`() {
        val factory = FakeQuickLaunchProcessFactory()
        val service = QuickLaunchProcessService.createForTest(factory)
        val key = key(targetName = "LyraClient", targetType = "Client", platform = "Linux")

        service.launch(key, artifact(targetName = "LyraClient", targetType = "Client", platform = "Linux"), commandLine())
        val previous = factory.processes.single()
        service.launch(key, artifact(targetName = "LyraClient", targetType = "Client", platform = "Linux"), commandLine())
        val replacement = factory.processes.last()

        assertTrue(previous.destroyed)
        assertTrue(replacement.started)
        assertFalse(replacement.destroyed)
        assertTrue(service.isRunning(key))
        assertEquals(setOf(key), service.runningKeys())

        previous.terminate()

        assertTrue(service.isRunning(key))
        assertEquals(setOf(key), service.runningKeys())
    }

    @Test
    fun `termination callback removes the key`() {
        val factory = FakeQuickLaunchProcessFactory()
        val service = QuickLaunchProcessService.createForTest(factory)
        val key = key(targetName = "LyraServer", targetType = "Server", platform = "Mac")

        service.launch(key, artifact(targetName = "LyraServer", targetType = "Server", platform = "Mac"), commandLine())
        factory.processes.single().terminate()

        assertFalse(service.isRunning(key))
        assertEquals(emptySet<QuickLaunchKey>(), service.runningKeys())
    }

    @Test
    fun `launch failure removes tracked handler and destroys process`() {
        val runFailure = IllegalStateException("executor failed")
        val failingProcess = FakeQuickLaunchProcess(runFailure = runFailure)
        val factory = FakeQuickLaunchProcessFactory { failingProcess }
        val service = QuickLaunchProcessService.createForTest(factory)
        val key = key(targetName = "LyraGame", targetType = "Game")

        val thrown = assertThrows(IllegalStateException::class.java) {
            service.launch(key, artifact(targetName = "LyraGame", targetType = "Game"), commandLine())
        }

        assertSame(runFailure, thrown)
        assertTrue(failingProcess.destroyed)
        assertFalse(service.isRunning(key))
        assertEquals(emptySet<QuickLaunchKey>(), service.runningKeys())
    }

    @Test
    fun `stop removes tracked handler and destroys process`() {
        val factory = FakeQuickLaunchProcessFactory()
        val service = QuickLaunchProcessService.createForTest(factory)
        val key = key(targetName = "LyraGame", targetType = "Game")

        service.launch(key, artifact(targetName = "LyraGame", targetType = "Game"), commandLine())
        val process = factory.processes.single()
        service.stop(key)

        assertTrue(process.destroyed)
        assertTrue(service.isRunning(key))
        process.terminate()
        assertFalse(service.isRunning(key))
        assertEquals(emptySet<QuickLaunchKey>(), service.runningKeys())
    }

    @Test
    fun `stopAll removes tracked handlers and destroys processes`() {
        val factory = FakeQuickLaunchProcessFactory()
        val service = QuickLaunchProcessService.createForTest(factory)
        val game = key(targetName = "LyraGame", targetType = "Game")
        val server = key(entryIndex = 1, targetName = "LyraServer", targetType = "Server", platform = "Linux")

        service.launch(game, artifact(targetName = "LyraGame", targetType = "Game"), commandLine())
        service.launch(server, artifact(targetName = "LyraServer", targetType = "Server", platform = "Linux"), commandLine())
        service.stopAll()

        assertTrue(factory.processes.all { it.destroyed })
        factory.processes.forEach { it.terminate() }
        assertFalse(service.isRunning(game))
        assertFalse(service.isRunning(server))
        assertEquals(emptySet<QuickLaunchKey>(), service.runningKeys())
    }

    @Test
    fun `dispose stops tracked handlers and clears running keys`() {
        val factory = FakeQuickLaunchProcessFactory()
        val service = QuickLaunchProcessService.createForTest(factory)
        val game = key(targetName = "LyraGame", targetType = "Game")
        val server = key(entryIndex = 1, targetName = "LyraServer", targetType = "Server", platform = "Linux")

        service.launch(game, artifact(targetName = "LyraGame", targetType = "Game"), commandLine())
        service.launch(server, artifact(targetName = "LyraServer", targetType = "Server", platform = "Linux"), commandLine())
        service.dispose()

        assertTrue(factory.processes.all { it.destroyed })
        factory.processes.forEach { it.terminate() }
        assertEquals(emptySet<QuickLaunchKey>(), service.runningKeys())
    }

    @Test
    fun `duplicate target platform entries can run independently`() {
        val factory = FakeQuickLaunchProcessFactory()
        val service = QuickLaunchProcessService.createForTest(factory)
        val first = QuickLaunchKey(
            configurationName = "Three Clients",
            entryIndex = 0,
            targetName = "LyraClient",
            targetType = "Client",
            platform = "Win64",
        )
        val second = QuickLaunchKey(
            configurationName = "Three Clients",
            entryIndex = 1,
            targetName = "LyraClient",
            targetType = "Client",
            platform = "Win64",
        )

        val artifact = artifact(targetName = "LyraClient", targetType = "Client")
        service.launch(first, artifact, GeneralCommandLine("first"))
        service.launch(second, artifact, GeneralCommandLine("second"))

        assertEquals(setOf(first, second), service.runningKeys())
        assertEquals(2, factory.processes.size)
    }

    @Test
    fun `running launches retain exact key artifact and title metadata`() {
        val factory = FakeQuickLaunchProcessFactory()
        val service = QuickLaunchProcessService.createForTest(factory)
        val key = key(targetName = "LyraClient", targetType = "Client")
        val artifact = artifact(targetName = "LyraClient", targetType = "Client")

        service.launch(key, artifact, commandLine())

        val running = service.runningLaunches().single()
        assertEquals(key, running.key)
        assertEquals(artifact, running.artifact)
        assertEquals("Unreal Default 1: LyraClient Client Win64", running.title)
    }

    @Test
    fun `stop and wait invokes callback after every selected process terminates`() {
        val factory = FakeQuickLaunchProcessFactory()
        val service = QuickLaunchProcessService.createForTest(factory)
        val first = key(entryIndex = 0, targetName = "LyraClient", targetType = "Client")
        val second = key(entryIndex = 1, targetName = "LyraClient", targetType = "Client")
        val untouched = key(entryIndex = 2, targetName = "LyraServer", targetType = "Server")
        val clientArtifact = artifact(targetName = "LyraClient", targetType = "Client")
        service.launch(first, clientArtifact, commandLine())
        service.launch(second, clientArtifact, commandLine())
        service.launch(untouched, artifact(targetName = "LyraServer", targetType = "Server"), commandLine())
        val callbacks = mutableListOf<QuickLaunchStopResult>()

        service.stopAndWait(service.runningLaunches().filter { it.key in setOf(first, second) }) { result ->
            callbacks += result
        }

        assertTrue(factory.processes[0].destroyed)
        assertTrue(factory.processes[1].destroyed)
        assertFalse(factory.processes[2].destroyed)
        assertEquals(emptyList<QuickLaunchStopResult>(), callbacks)

        factory.processes[0].terminate()
        assertEquals(emptyList<QuickLaunchStopResult>(), callbacks)

        factory.processes[1].terminate()
        assertEquals(listOf(QuickLaunchStopResult.Completed), callbacks)
        assertEquals(setOf(untouched), service.runningKeys())
    }

    @Test
    fun `stop and wait latches destroy failure despite later natural termination`() {
        val destroyFailure = IllegalStateException("cannot destroy")
        val process = FakeQuickLaunchProcess(destroyFailure = destroyFailure)
        val service = QuickLaunchProcessService.createForTest(FakeQuickLaunchProcessFactory { process })
        val key = key(targetName = "LyraClient", targetType = "Client")
        service.launch(key, artifact(targetName = "LyraClient", targetType = "Client"), commandLine())
        val selected = service.runningLaunches()
        val results = mutableListOf<QuickLaunchStopResult>()

        service.stopAndWait(selected, results::add)

        assertEquals(listOf(QuickLaunchStopResult.Failed(destroyFailure)), results)

        process.terminate()

        assertEquals(listOf(QuickLaunchStopResult.Failed(destroyFailure)), results)
    }

    @Test
    fun `stop and wait preserves newer launch that reused a captured key`() {
        val factory = FakeQuickLaunchProcessFactory()
        val service = QuickLaunchProcessService.createForTest(factory)
        val key = key(targetName = "LyraClient", targetType = "Client")
        val artifact = artifact(targetName = "LyraClient", targetType = "Client")
        service.launch(key, artifact, commandLine())
        val captured = service.runningLaunches()

        service.launch(key, artifact, commandLine())
        val replacement = factory.processes.last()
        var result: QuickLaunchStopResult? = null

        service.stopAndWait(captured) { result = it }

        assertEquals(QuickLaunchStopResult.Completed, result)
        assertFalse(replacement.destroyed)
        assertEquals(listOf(key), service.runningLaunches().map { it.key })
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

    private fun commandLine(): GeneralCommandLine =
        GeneralCommandLine("/tmp/MyGame")
}

private class FakeQuickLaunchProcessFactory(
    private val processProvider: () -> FakeQuickLaunchProcess = { FakeQuickLaunchProcess() },
) : QuickLaunchProcessFactory {
    val titles = mutableListOf<String>()
    val processes = mutableListOf<FakeQuickLaunchProcess>()

    override fun create(commandLine: GeneralCommandLine, title: String): QuickLaunchProcess {
        titles += title
        return processProvider().also { processes += it }
    }
}

private class FakeQuickLaunchProcess(
    private val runFailure: RuntimeException? = null,
    private val destroyFailure: RuntimeException? = null,
) : QuickLaunchProcess {
    var destroyed: Boolean = false
        private set

    var started: Boolean = false
        private set

    private var terminated: Boolean = false
    private val terminationListeners = mutableListOf<() -> Unit>()

    override val isProcessTerminated: Boolean
        get() = terminated

    override fun destroy() {
        destroyed = true
        destroyFailure?.let { throw it }
    }

    override fun addTerminationListener(listener: () -> Unit) {
        terminationListeners += listener
    }

    override fun run() {
        runFailure?.let { throw it }
        started = true
    }

    fun terminate() {
        terminated = true
        terminationListeners.forEach { it() }
    }
}
