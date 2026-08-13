package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.command.UnrealCommand
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.GeneralCommandLine.ParentEnvironmentType
import com.intellij.execution.configurations.ParametersList
import com.intellij.execution.process.ProcessOutputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class UnrealWorkflowProcessTest {
    @Test
    fun `start reports lifecycle output and exit code exactly once`() {
        val listener = RecordingListener()
        val process = UnrealWorkflowProcessFactory.create(
            shellCommand("printf 'standard output\\n'; printf 'standard error\\n' >&2; exit 7"),
        )

        process.start(listener)

        assertTrue("process did not terminate", listener.terminated.await(10, TimeUnit.SECONDS))
        assertEquals(1, listener.startedCount)
        assertEquals(listOf(7), listener.exitCodes)
        assertTrue(listener.outputFor(ProcessOutputType.STDOUT).contains("standard output"))
        assertTrue(listener.outputFor(ProcessOutputType.STDERR).contains("standard error"))
        assertTrue(process.isProcessTerminated)
    }

    @Test
    fun `destroy requests termination without reporting success`() {
        val listener = RecordingListener()
        val process = UnrealWorkflowProcessFactory.create(shellCommand("while :; do sleep 1; done"))

        process.start(listener)
        assertTrue("process did not start", listener.started.await(10, TimeUnit.SECONDS))
        assertFalse(process.isProcessTerminated)

        process.destroy()

        assertTrue("destroyed process did not terminate", listener.terminated.await(10, TimeUnit.SECONDS))
        assertEquals(1, listener.startedCount)
        assertEquals(1, listener.exitCodes.size)
        assertTrue("destroy must not fabricate exit code zero", listener.exitCodes.single() != 0)
        assertTrue(process.isProcessTerminated)
    }

    @Test
    fun `UI work submitted from a process callback runs on EDT`() {
        val completed = CountDownLatch(1)
        var ranOnEdt = false

        val caller = Thread {
            runOnEdt {
                ranOnEdt = SwingUtilities.isEventDispatchThread()
                completed.countDown()
            }
        }
        caller.start()
        caller.join(10_000)

        assertFalse("caller thread did not finish", caller.isAlive)
        assertTrue("EDT task did not run", completed.await(10, TimeUnit.SECONDS))
        assertTrue("task did not run on EDT", ranOnEdt)
    }

    @Test
    fun `debug configuration preserves the resolved launch command`() {
        val commandLine = GeneralCommandLine("/Workspace/Lyra/Binaries/Mac/LyraClient")
            .withWorkingDirectory(Path.of("/Workspace/Lyra/Binaries/Mac"))
            .withEnvironment(mapOf("UE_LOG" to "verbose"))
        commandLine.withParentEnvironmentType(ParentEnvironmentType.NONE)
        commandLine.addParameters("-windowed", "-ExecCmds=stat fps")

        val parameters = debugConfigurationParameters(commandLine)

        assertEquals(commandLine.exePath, parameters.exePath)
        assertEquals(commandLine.workingDirectory.toString(), parameters.workingDirectory)
        assertEquals(commandLine.parametersList.list, ParametersList.parse(parameters.programParameters).toList())
        assertEquals(mapOf("UE_LOG" to "verbose"), parameters.envs)
        assertFalse(parameters.isPassParentEnvs)
    }

    private fun shellCommand(script: String): UnrealCommand =
        UnrealCommand(
            executable = "/bin/sh",
            arguments = listOf("-c", script),
            workingDirectory = System.getProperty("java.io.tmpdir"),
        )

    private class RecordingListener : UnrealWorkflowProcessListener {
        val started = CountDownLatch(1)
        val terminated = CountDownLatch(1)
        val output = CopyOnWriteArrayList<Pair<String, ProcessOutputType>>()
        val exitCodes = CopyOnWriteArrayList<Int>()

        @Volatile
        var startedCount = 0

        override fun started() {
            startedCount++
            started.countDown()
        }

        override fun output(text: String, outputType: ProcessOutputType) {
            output += text to outputType
        }

        override fun terminated(exitCode: Int) {
            exitCodes += exitCode
            terminated.countDown()
        }

        fun outputFor(type: ProcessOutputType): String =
            output.filter { it.second === type }.joinToString(separator = "") { it.first }
    }
}
