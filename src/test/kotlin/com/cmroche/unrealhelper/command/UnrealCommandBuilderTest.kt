package com.cmroche.unrealhelper.command

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class UnrealCommandBuilderTest {
    @Test
    fun `build command uses target name for UBT target and includes global args`() {
        val command = UnrealCommandBuilder.build(
            context(
                targetName = "MyGameEditor",
                targetType = "Game",
                extraArguments = "-game -log",
            ),
        )

        assertEquals("Unreal Build MyGameEditor Game Win64", command.title)
        assertEquals(
            "/Engines/UE_5.6/Engine/Binaries/DotNET/UnrealBuildTool/UnrealBuildTool",
            command.executable,
        )
        assertEquals(
            listOf(
                "MyGameEditor",
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
        val command = UnrealCommandBuilder.cook(context(targetName = "MyGameServer", targetType = "Server", platform = "Linux"))

        assertEquals("Unreal Cook MyGameServer Server Linux", command.title)
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
            context(
                targetName = "MyGameClient",
                targetType = "Client",
                packageDirectory = Path.of("/Artifacts/MyGame"),
                buildConfiguration = "Shipping",
            ),
        )

        assertEquals("Unreal Package MyGameClient Client Win64", command.title)
        assertEquals("/Engines/UE_5.6/Engine/Build/BatchFiles/RunUAT.sh", command.executable)
        assertEquals(
            listOf(
                "BuildCookRun",
                "-project=/Workspace/MyGame/MyGame.uproject",
                "-noP4",
                "-build",
                "-cook",
                "-stage",
                "-pak",
                "-archive",
                "-archivedirectory=/Artifacts/MyGame",
                "-targetplatform=Win64",
                "-clientconfig=Shipping",
                "-utf8output",
            ),
            command.arguments,
        )
        assertEquals("/Workspace/MyGame", command.workingDirectory)
    }

    @Test
    fun `quoted extra args remain one argument`() {
        val command = UnrealCommandBuilder.build(context(extraArguments = "-ExecCmds=\"stat fps\" -log"))

        assertEquals(
            listOf("-ExecCmds=stat fps", "-log"),
            command.arguments.takeLast(2),
        )
    }

    @Test
    fun `quoted extra args preserve whitespace inside argument values`() {
        val command = UnrealCommandBuilder.cook(context(extraArguments = "-ExecCmds=\" stat fps \""))

        assertEquals("-ExecCmds= stat fps ", command.arguments.last())
    }

    @Test
    fun `shell line quotes project paths with spaces`() {
        val command = UnrealCommandBuilder.build(
            context(
                uprojectPath = Path.of("/Workspace/My Game/My Game.uproject"),
                engineRoot = Path.of("/Engines/Epic Games/UE_5.6"),
            ),
        )

        assertEquals(
            "'/Engines/Epic Games/UE_5.6/Engine/Binaries/DotNET/UnrealBuildTool/UnrealBuildTool' " +
                "MyGame Win64 Development '-Project=/Workspace/My Game/My Game.uproject' -WaitMutex",
            command.shellLine(),
        )
    }

    @Test
    fun `shell line escapes quotes in global args`() {
        val command = UnrealCommandBuilder.build(context(extraArguments = "-ExecCmds=\"stat 'fps'\""))

        assertEquals(
            "/Engines/UE_5.6/Engine/Binaries/DotNET/UnrealBuildTool/UnrealBuildTool " +
                "MyGame Win64 Development -Project=/Workspace/MyGame/MyGame.uproject -WaitMutex " +
                "'-ExecCmds=stat '\\''fps'\\'''",
            command.shellLine(),
        )
    }

    @Test
    fun `windows executable resolution uses exe and bat files`() {
        val build = UnrealCommandBuilder.build(context(), osName = "Windows 11")
        val cook = UnrealCommandBuilder.cook(context(), osName = "Windows 11")

        assertEquals(
            "/Engines/UE_5.6/Engine/Binaries/DotNET/UnrealBuildTool/UnrealBuildTool.exe",
            build.executable,
        )
        assertEquals("/Engines/UE_5.6/Engine/Build/BatchFiles/RunUAT.bat", cook.executable)
    }

    private fun context(
        uprojectPath: Path = Path.of("/Workspace/MyGame/MyGame.uproject"),
        engineRoot: Path = Path.of("/Engines/UE_5.6"),
        workspaceRoot: Path = Path.of("/Workspace/MyGame"),
        packageDirectory: Path = Path.of("/Workspace/MyGame/Packages"),
        buildConfiguration: String = "Development",
        targetName: String = "MyGame",
        targetType: String = "Game",
        platform: String = "Win64",
        extraArguments: String = "",
    ): UnrealCommandContext =
        UnrealCommandContext(
            uprojectPath = uprojectPath,
            engineRoot = engineRoot,
            workspaceRoot = workspaceRoot,
            packageDirectory = packageDirectory,
            buildConfiguration = buildConfiguration,
            targetName = targetName,
            targetType = targetType,
            platform = platform,
            extraArguments = extraArguments,
        )
}
