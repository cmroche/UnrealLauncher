package com.cmroche.unrealhelper.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class UnrealCommandBuilderTest {
    @Test
    fun `build command includes target platform configuration project path and global args`() {
        val command = UnrealCommandBuilder.build(context(extraArguments = "-game -log"))

        assertEquals("Unreal Build MyGame Win64", command.title)
        assertEquals(
            "/Engines/UE_5.6/Engine/Binaries/DotNET/UnrealBuildTool/UnrealBuildTool",
            command.executable,
        )
        assertEquals(
            listOf(
                "MyGame",
                "Win64",
                "Development",
                "-Project=/Workspace/MyGame/MyGame.uproject",
                "-WaitMutex",
                "-game",
                "-log",
            ),
            command.arguments,
        )
        assertEquals("/Workspace/MyGame", command.workingDirectory)
        assertEquals(listOf(command.executable) + command.arguments, command.asList())
    }

    @Test
    fun `cook command uses RunUAT BuildCookRun and skipstage`() {
        val command = UnrealCommandBuilder.cook(context(platform = "Linux"))

        assertEquals("Unreal Cook MyGame Linux", command.title)
        assertEquals("/Engines/UE_5.6/Engine/Build/BatchFiles/RunUAT.sh", command.executable)
        assertEquals(
            listOf(
                "BuildCookRun",
                "-project=/Workspace/MyGame/MyGame.uproject",
                "-noP4",
                "-cook",
                "-skipstage",
                "-skippackage",
                "-targetplatform=Linux",
                "-clientconfig=Development",
                "-utf8output",
            ),
            command.arguments,
        )
        assertEquals("/Workspace/MyGame", command.workingDirectory)
    }

    @Test
    fun `package command includes archive and configured archive directory`() {
        val command = UnrealCommandBuilder.packageProject(
            context(packageDirectory = Path.of("/Artifacts/MyGame"), buildConfiguration = "Shipping"),
        )

        assertEquals("Unreal Package MyGame Win64", command.title)
        assertTrue(command.arguments.contains("-archive"))
        assertTrue(command.arguments.contains("-archivedirectory=/Artifacts/MyGame"))
        assertTrue(command.arguments.contains("-clientconfig=Shipping"))
    }

    @Test
    fun `quoted extra args remain one argument`() {
        val command = UnrealCommandBuilder.build(context(extraArguments = "-ExecCmds=\"stat fps\" -log"))

        assertEquals(
            listOf("-ExecCmds=stat fps", "-log"),
            command.arguments.takeLast(2),
        )
    }

    private fun context(
        uprojectPath: Path = Path.of("/Workspace/MyGame/MyGame.uproject"),
        engineRoot: Path = Path.of("/Engines/UE_5.6"),
        workspaceRoot: Path = Path.of("/Workspace/MyGame"),
        packageDirectory: Path = Path.of("/Workspace/MyGame/Packages"),
        buildConfiguration: String = "Development",
        targetType: String = "MyGame",
        platform: String = "Win64",
        extraArguments: String = "",
    ): UnrealCommandContext =
        UnrealCommandContext(
            uprojectPath = uprojectPath,
            engineRoot = engineRoot,
            workspaceRoot = workspaceRoot,
            packageDirectory = packageDirectory,
            buildConfiguration = buildConfiguration,
            targetType = targetType,
            platform = platform,
            extraArguments = extraArguments,
        )
}
