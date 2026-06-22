package com.cmroche.unrealhelper.launch

import com.intellij.execution.configurations.GeneralCommandLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class QuickLaunchProcessServiceTest {
    @Test
    fun `launch tracks key and executor title`() {
        val factory = FakeQuickLaunchProcessFactory()
        val service = QuickLaunchProcessService.createForTest(factory)
        val key = QuickLaunchKey(targetType = "Game", platform = "Win64")

        service.launch(key, commandLine())

        assertTrue(service.isRunning(key))
        assertEquals(setOf(key), service.runningKeys())
        assertEquals(listOf("Unreal Game Win64"), factory.titles)
        assertEquals(1, factory.processes.size)
        assertTrue(factory.processes.single().started)
    }

    @Test
    fun `relaunching same key stops previous handler and replaces it`() {
        val factory = FakeQuickLaunchProcessFactory()
        val service = QuickLaunchProcessService.createForTest(factory)
        val key = QuickLaunchKey(targetType = "Client", platform = "Linux")

        service.launch(key, commandLine())
        val previous = factory.processes.single()
        service.launch(key, commandLine())
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
        val key = QuickLaunchKey(targetType = "Server", platform = "Mac")

        service.launch(key, commandLine())
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
        val key = QuickLaunchKey(targetType = "Game", platform = "Win64")

        val thrown = assertThrows(IllegalStateException::class.java) {
            service.launch(key, commandLine())
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
        val key = QuickLaunchKey(targetType = "Game", platform = "Win64")

        service.launch(key, commandLine())
        val process = factory.processes.single()
        service.stop(key)

        assertTrue(process.destroyed)
        assertFalse(service.isRunning(key))
        assertEquals(emptySet<QuickLaunchKey>(), service.runningKeys())
    }

    @Test
    fun `stopAll removes tracked handlers and destroys processes`() {
        val factory = FakeQuickLaunchProcessFactory()
        val service = QuickLaunchProcessService.createForTest(factory)
        val game = QuickLaunchKey(targetType = "Game", platform = "Win64")
        val server = QuickLaunchKey(targetType = "Server", platform = "Linux")

        service.launch(game, commandLine())
        service.launch(server, commandLine())
        service.stopAll()

        assertTrue(factory.processes.all { it.destroyed })
        assertFalse(service.isRunning(game))
        assertFalse(service.isRunning(server))
        assertEquals(emptySet<QuickLaunchKey>(), service.runningKeys())
    }

    @Test
    fun `dispose stops tracked handlers and clears running keys`() {
        val factory = FakeQuickLaunchProcessFactory()
        val service = QuickLaunchProcessService.createForTest(factory)
        val game = QuickLaunchKey(targetType = "Game", platform = "Win64")
        val server = QuickLaunchKey(targetType = "Server", platform = "Linux")

        service.launch(game, commandLine())
        service.launch(server, commandLine())
        service.dispose()

        assertTrue(factory.processes.all { it.destroyed })
        assertEquals(emptySet<QuickLaunchKey>(), service.runningKeys())
    }

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
        terminated = true
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
