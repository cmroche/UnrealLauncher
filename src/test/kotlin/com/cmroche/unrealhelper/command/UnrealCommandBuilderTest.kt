package com.cmroche.unrealhelper.command

import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import com.cmroche.unrealhelper.workflow.UnrealCookMode
import com.cmroche.unrealhelper.workflow.artifactCookDirectory
import org.junit.Assert.assertEquals
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
                "-Target=LyraClient Win64 Development -Project=\"/Workspace/Lyra/Lyra.uproject\"",
                "-Target=LyraServer Win64 Development -Project=\"/Workspace/Lyra/Lyra.uproject\"",
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
    fun `package build batch includes host UnrealPak for a source engine`() {
        val command = UnrealCommandBuilder.buildBatch(
            listOf(context(targetName = "LyraClient", targetType = "Client", platform = "Mac")),
            includePackagingTools = true,
            isEngineInstalled = false,
            osName = "Mac OS X",
        )

        assertEquals("Unreal Build 2 target(s)", command.title)
        assertEquals(
            "-Target=UnrealPak Mac Development -Project=\"/Workspace/Lyra/Lyra.uproject\"",
            command.arguments[1],
        )
    }

    @Test
    fun `package build batch uses prebuilt UnrealPak from an installed engine`() {
        val command = UnrealCommandBuilder.buildBatch(
            listOf(context(targetName = "LyraClient", targetType = "Client", platform = "Mac")),
            includePackagingTools = true,
            isEngineInstalled = true,
            osName = "Mac OS X",
        )

        assertEquals("Unreal Build 1 target(s)", command.title)
        assertTrue(command.arguments.none { it.contains("UnrealPak") })
    }

    @Test
    fun `cook targets the artifact and skips every other phase`() {
        val context = context(targetName = "LyraClient", targetType = "Client")
        val command = UnrealCommandBuilder.cook(
            context,
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
                "-CookOutputDir=${artifactCookDirectory(context.artifact)}",
                "-client",
                "-targetplatform=Win64",
                "-clientconfig=Development",
            ),
            command.arguments,
        )
    }

    @Test
    fun `incremental cook requests cookincremental`() {
        val command = UnrealCommandBuilder.cook(context(), UnrealCookMode.INCREMENTAL)

        assertTrue(command.arguments.contains("-cookincremental"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cook rejects editor target`() {
        UnrealCommandBuilder.cook(
            context(targetName = "LyraEditor", targetType = "Editor"),
            UnrealCookMode.FULL,
        )
    }

    @Test
    fun `cook writes to its artifact specific output directory`() {
        val output = Path.of("/Workspace/Lyra/Saved/UnrealHelper/Cooked/LyraClient/WindowsClient")
        val command = UnrealCommandBuilder.cook(context(), UnrealCookMode.FULL, output)

        assertTrue(command.arguments.contains("-CookOutputDir=$output"))
    }

    @Test
    fun `server stage uses server role and skips completed prerequisites`() {
        val command = UnrealCommandBuilder.stage(
            context(targetName = "LyraServer", targetType = "Server"),
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
                "-CookOutputDir=/cook/LyraServer",
                "-stagingdirectory=/stage/LyraServer",
                "-server",
                "-noclient",
                "-servertargetplatform=Win64",
                "-serverconfig=Development",
            ),
            UnrealCommandBuilder.stage(
                context(targetName = "LyraServer", targetType = "Server"),
                Path.of("/cook/LyraServer"),
                Path.of("/stage/LyraServer"),
            ).arguments,
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
            ),
            Path.of("/cook/LyraClient"),
            Path.of("/stage/LyraClient"),
            Path.of("/Artifacts/Lyra/LyraClient"),
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
                "-CookOutputDir=/cook/LyraClient",
                "-stagingdirectory=/stage/LyraClient",
                "-package",
                "-pak",
                "-archive",
                "-archivedirectory=/Artifacts/Lyra/LyraClient",
                "-client",
                "-targetplatform=Win64",
                "-clientconfig=Shipping",
            ),
            command.arguments,
        )
    }

    @Test
    fun `batched target descriptor quotes and escapes project paths`() {
        val command = UnrealCommandBuilder.buildBatch(
            listOf(context(uprojectPath = Path.of("/Workspace/My Project/Lyra.uproject"))),
        )
        assertEquals(
            "-Target=LyraGame Win64 Development -Project=\"/Workspace/My Project/Lyra.uproject\"",
            command.arguments.first(),
        )
    }

    @Test
    fun `batched target descriptor preserves Windows slashes and escapes embedded quotes`() {
        val command = UnrealCommandBuilder.buildBatch(
            listOf(context(uprojectPath = Path.of("C:\\My \"Game\"\\Lyra.uproject"))),
            osName = "Windows 11",
        )

        assertEquals(
            "-Target=LyraGame Win64 Development -Project=\"C:\\My \\\"Game\\\"\\Lyra.uproject\"",
            command.arguments.first(),
        )
    }

    @Test
    fun `artifact context exposes target getters`() {
        val context = context(targetName = "LyraServer", targetType = "Server", platform = "Linux")

        assertEquals(Path.of("/Workspace/Lyra/Lyra.uproject"), context.uprojectPath)
        assertEquals("LyraServer", context.targetName)
        assertEquals("Server", context.targetType)
        assertEquals("Linux", context.platform)
        assertEquals("Development", context.buildConfiguration)
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
        )
}
