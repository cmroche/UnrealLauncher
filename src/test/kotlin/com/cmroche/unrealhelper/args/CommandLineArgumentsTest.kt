package com.cmroche.unrealhelper.args

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandLineArgumentsTest {
    @Test
    fun `parse splits whitespace outside quotes`() {
        assertEquals(
            listOf("-game", "-windowed", "-resx=1080", "-resy=1920"),
            CommandLineArguments.parse("-game -windowed -resx=1080 -resy=1920"),
        )
    }

    @Test
    fun `parse preserves quoted values`() {
        assertEquals(
            listOf("-game", "-ExecCmds=\"stat fps\"", "-log"),
            CommandLineArguments.parse("-game -ExecCmds=\"stat fps\" -log"),
        )
    }

    @Test
    fun `editor text renders one argument per line`() {
        assertEquals(
            "-game\n-windowed\n-ExecCmds=\"stat fps\"",
            CommandLineArguments.toEditorText("-game -windowed -ExecCmds=\"stat fps\""),
        )
    }

    @Test
    fun `editor text collapses to command line`() {
        assertEquals(
            "-game -windowed -log",
            CommandLineArguments.fromEditorText(
                """
                -game
                -windowed

                -log
                """.trimIndent(),
            ),
        )
    }
}

