package com.cmroche.unrealhelper.command

import org.junit.Assert.assertEquals
import org.junit.Test

class UnrealCommandTest {
    @Test
    fun `posix shell quoting preserves empty arguments`() {
        assertEquals("''", "".quoteForShell())
    }

    @Test
    fun `windows shell line quotes paths and escapes embedded quotes`() {
        val command = UnrealCommand(
            title = "Unreal Build MyGame Game Win64",
            executable = "C:\\Program Files\\Epic Games\\UE_5.6\\Engine\\Binaries\\DotNET\\UnrealBuildTool\\UnrealBuildTool.exe",
            arguments = listOf(
                "MyGame",
                "Win64",
                "Development",
                "-Project=C:\\Workspace\\My Game\\MyGame.uproject",
                "-ExecCmds=stat \"fps\"",
                "",
            ),
            workingDirectory = "C:\\Workspace\\My Game",
        )

        assertEquals(
            "\"C:\\Program Files\\Epic Games\\UE_5.6\\Engine\\Binaries\\DotNET\\UnrealBuildTool\\UnrealBuildTool.exe\" " +
                "MyGame Win64 Development \"-Project=C:\\Workspace\\My Game\\MyGame.uproject\" " +
                "\"-ExecCmds=stat \\\"fps\\\"\" \"\"",
            command.shellLine(osName = "Windows 11"),
        )
    }
}
