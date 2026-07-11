package com.cmroche.unrealhelper.command

import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import com.cmroche.unrealhelper.workflow.UnrealCookMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class UnrealCommandBuilderTest {
    @Test
    fun `build batch emits one target descriptor per distinct artifact`() {
        val command = UnrealCommandBuilder.buildBatch(
            listOf(
                context(targetName = "LyraClient", targetType = "Client"),
                context(targetName = "LyraServer", targetType = "Server"),
                context(targetName = "LyraClient", targetType = "Client"),
            ),
        )

        assertEquals("Unreal Build 2 target(s)", command.title)
        assertEquals(
            "/Engines/UE_5.6/Engine/Binaries/DotNET/UnrealBuildTool/UnrealBuildTool",
            command.executable,
        )
        assertEquals(
            listOf(
                "-Target=LyraClient Win64 Development -Project=/Workspace/Lyra/Lyra.uproject",
                "-Target=LyraServer Win64 Development -Project=/Workspace/Lyra/Lyra.uproject",
                "-WaitMutex",
            ),
            command.arguments,
        )
        assertEquals("/Workspace/Lyra", command.workingDirectory)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `build batch rejects an empty context list`() {
        UnrealCommandBuilder.buildBatch(emptyList())
    }

    @Test
    fun `cook targets the artifact and skips every other phase`() {
        val command = UnrealCommandBuilder.cook(
            context(targetName = "LyraClient", targetType = "Client", extraArguments = "-game -log"),
            UnrealCookMode.FULL,
        )

        assertEquals("Unreal Cook LyraClient Client Win64", command.title)
        assertEquals("/Engines/UE_5.6/Engine/Build/BatchFiles/RunUAT.sh", command.executable)
        assertEquals(
            listOf(
                "BuildCookRun",
                "-project=/Workspace/Lyra/Lyra.uproject",
                "-target=LyraClient",
                "-noP4",
                "-utf8output",
                "-skipbuild",
                "-cook",
                "-skipstage",
                "-skippackage",
                "-targetplatform=Win64",
                "-clientconfig=Development",
            ),
            command.arguments,
        )
        assertFalse(command.arguments.contains("-game"))
        assertFalse(command.arguments.contains("-log"))
    }

    @Test
    fun `incremental cook requests cookincremental`() {
        val command = UnrealCommandBuilder.cook(context(), UnrealCookMode.INCREMENTAL)

        assertTrue(command.arguments.contains("-cookincremental"))
    }

    @Test
    fun `server stage uses server role and skips completed prerequisites`() {
        val command = UnrealCommandBuilder.stage(
            context(targetName = "LyraServer", targetType = "Server", extraArguments = "-log"),
        )

        assertEquals("Unreal Stage LyraServer Server Win64", command.title)
        assertEquals(
            listOf(
                "BuildCookRun",
                "-project=/Workspace/Lyra/Lyra.uproject",
                "-target=LyraServer",
                "-noP4",
                "-utf8output",
                "-skipbuild",
                "-skipcook",
                "-stage",
                "-skippackage",
                "-server",
                "-noclient",
                "-servertargetplatform=Win64",
                "-serverconfig=Development",
            ),
            command.arguments,
        )
    }

    @Test
    fun `package skips completed prerequisites and preserves archive directory`() {
        val command = UnrealCommandBuilder.packageProject(
            context(
                targetName = "LyraClient",
                targetType = "Client",
                packageDirectory = Path.of("/Artifacts/Lyra"),
                buildConfiguration = "Shipping",
                extraArguments = "-log",
            ),
        )

        assertEquals("Unreal Package LyraClient Client Win64", command.title)
        assertEquals(
            listOf(
                "BuildCookRun",
                "-project=/Workspace/Lyra/Lyra.uproject",
                "-target=LyraClient",
                "-noP4",
                "-utf8output",
                "-skipbuild",
                "-skipcook",
                "-skipstage",
                "-package",
                "-pak",
                "-archive",
                "-archivedirectory=/Artifacts/Lyra",
                "-targetplatform=Win64",
                "-clientconfig=Shipping",
            ),
            command.arguments,
        )
    }

    @Test
    fun `artifact context exposes compatibility target getters`() {
        val context = context(targetName = "LyraServer", targetType = "Server", platform = "Linux")

        assertEquals(Path.of("/Workspace/Lyra/Lyra.uproject"), context.uprojectPath)
        assertEquals("LyraServer", context.targetName)
        assertEquals("Server", context.targetType)
        assertEquals("Linux", context.platform)
        assertEquals("Development", context.buildConfiguration)
    }

    @Test
    fun `legacy build command remains source compatible`() {
        val command = UnrealCommandBuilder.build(legacyContext(extraArguments = "-ExecCmds=\"stat fps\" -log"))

        assertEquals(
            listOf("-ExecCmds=stat fps", "-log"),
            command.arguments.takeLast(2),
        )
    }

    @Test
    fun `legacy cook command preserves whitespace inside quoted arguments`() {
        val command = UnrealCommandBuilder.cook(legacyContext(extraArguments = "-ExecCmds=\" stat fps \""))

        assertEquals("-ExecCmds= stat fps ", command.arguments.last())
    }

    @Test
    fun `legacy build shell line quotes project paths with spaces`() {
        val command = UnrealCommandBuilder.build(
            legacyContext(
                uprojectPath = Path.of("/Workspace/Lyra Game/Lyra Game.uproject"),
                engineRoot = Path.of("/Engines/Epic Games/UE_5.6"),
            ),
        )

        assertEquals(
            "'/Engines/Epic Games/UE_5.6/Engine/Binaries/DotNET/UnrealBuildTool/UnrealBuildTool' " +
                "LyraGame Win64 Development '-Project=/Workspace/Lyra Game/Lyra Game.uproject' -WaitMutex",
            command.shellLine(),
        )
    }

    @Test
    fun `legacy build shell line escapes quotes in global arguments`() {
        val command = UnrealCommandBuilder.build(legacyContext(extraArguments = "-ExecCmds=\"stat 'fps'\""))

        assertEquals(
            "/Engines/UE_5.6/Engine/Binaries/DotNET/UnrealBuildTool/UnrealBuildTool " +
                "LyraGame Win64 Development -Project=/Workspace/Lyra/Lyra.uproject -WaitMutex " +
                "'-ExecCmds=stat '\\''fps'\\'''",
            command.shellLine(),
        )
    }

    @Test
    fun `windows executable resolution uses exe and bat files`() {
        val build = UnrealCommandBuilder.buildBatch(listOf(context()), osName = "Windows 11")
        val cook = UnrealCommandBuilder.cook(context(), UnrealCookMode.FULL, osName = "Windows 11")

        assertEquals(
            "/Engines/UE_5.6/Engine/Binaries/DotNET/UnrealBuildTool/UnrealBuildTool.exe",
            build.executable,
        )
        assertEquals("/Engines/UE_5.6/Engine/Build/BatchFiles/RunUAT.bat", cook.executable)
    }

    private fun context(
        uprojectPath: Path = Path.of("/Workspace/Lyra/Lyra.uproject"),
        engineRoot: Path = Path.of("/Engines/UE_5.6"),
        workspaceRoot: Path = Path.of("/Workspace/Lyra"),
        packageDirectory: Path = Path.of("/Workspace/Lyra/Packages"),
        buildConfiguration: String = "Development",
        targetName: String = "LyraGame",
        targetType: String = "Game",
        platform: String = "Win64",
        extraArguments: String = "",
    ): UnrealCommandContext =
        UnrealCommandContext(
            artifact = UnrealArtifactKey(
                projectPath = uprojectPath,
                targetName = targetName,
                targetType = targetType,
                platform = platform,
                buildConfiguration = buildConfiguration,
            ),
            engineRoot = engineRoot,
            workspaceRoot = workspaceRoot,
            packageDirectory = packageDirectory,
            extraArguments = extraArguments,
        )

    private fun legacyContext(
        uprojectPath: Path = Path.of("/Workspace/Lyra/Lyra.uproject"),
        engineRoot: Path = Path.of("/Engines/UE_5.6"),
        workspaceRoot: Path = Path.of("/Workspace/Lyra"),
        packageDirectory: Path = Path.of("/Workspace/Lyra/Packages"),
        buildConfiguration: String = "Development",
        targetName: String = "LyraGame",
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
