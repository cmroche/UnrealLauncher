package com.cmroche.unrealhelper.run

import com.intellij.execution.configurations.GeneralCommandLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalArgsCommandLineInjectorTest {
    @Test
    fun `arguments to inject skip existing exact arguments`() {
        assertEquals(
            listOf("-windowed", "-resx=1920"),
            GlobalArgsCommandLineInjector.argumentsToInject(
                existingArguments = listOf("-game", "-log"),
                globalCommandLine = "-game -windowed -log -resx=1920",
            ),
        )
    }

    @Test
    fun `arguments to inject parse quoted values`() {
        assertEquals(
            listOf("-ExecCmds=stat fps", "-log"),
            GlobalArgsCommandLineInjector.argumentsToInject(
                existingArguments = emptyList(),
                globalCommandLine = "\"-ExecCmds=stat fps\" -log",
            ),
        )
    }

    @Test
    fun `inject appends missing arguments to command line`() {
        val commandLine = GeneralCommandLine("Game.exe")
        commandLine.addParameter("-game")

        assertTrue(GlobalArgsCommandLineInjector.inject(commandLine, "-game -log -windowed"))

        assertEquals(listOf("-game", "-log", "-windowed"), commandLine.parametersList.list)
    }

    @Test
    fun `inject returns false when all arguments already exist`() {
        val commandLine = GeneralCommandLine("Game.exe")
        commandLine.addParameters("-game", "-log")

        assertFalse(GlobalArgsCommandLineInjector.inject(commandLine, "-game -log"))

        assertEquals(listOf("-game", "-log"), commandLine.parametersList.list)
    }
}
