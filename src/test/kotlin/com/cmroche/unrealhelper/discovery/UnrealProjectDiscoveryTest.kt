package com.cmroche.unrealhelper.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class UnrealProjectDiscoveryTest {
    @JvmField
    @Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `discovers uproject targets and platforms from project files`() {
        val root = temporaryFolder.newFolder("MyGame").toPath()
        Files.writeString(root.resolve("MyGame.uproject"), "{}")
        Files.createDirectories(root.resolve("Source"))
        Files.createDirectories(root.resolve("Platforms").resolve("PS5"))
        Files.writeString(
            root.resolve("Source").resolve("MyGame.Target.cs"),
            """
            public class MyGameTarget : TargetRules
            {
                public MyGameTarget(TargetInfo Target) : base(Target)
                {
                    Type = TargetType.Game;
                }
            }
            """.trimIndent(),
        )
        Files.writeString(
            root.resolve("Source").resolve("MyGameClient.Target.cs"),
            """
            public class MyGameClientTarget : TargetRules
            {
                public MyGameClientTarget(TargetInfo Target) : base(Target)
                {
                    Type = TargetType.Client;
                }
            }
            """.trimIndent(),
        )
        Files.writeString(
            root.resolve("Source").resolve("MyGameServer.Target.cs"),
            """
            public class MyGameServerTarget : TargetRules
            {
                public MyGameServerTarget(TargetInfo Target) : base(Target)
                {
                    Type = TargetType.Server;
                }
            }
            """.trimIndent(),
        )
        Files.writeString(
            root.resolve("Source").resolve("MyGame.Build.cs"),
            "if (Target.Platform == UnrealTargetPlatform.Win64) { }",
        )

        val result = UnrealProjectDiscovery.discover(root)

        assertEquals(root.toString(), result.workspaceRoot)
        assertEquals(root.resolve("MyGame.uproject").toString(), result.uprojectPath)
        assertEquals(
            listOf(
                DiscoveredUnrealTarget("MyGame", UnrealTargetType.Game, "Source${File.separator}MyGame.Target.cs"),
                DiscoveredUnrealTarget("MyGameClient", UnrealTargetType.Client, "Source${File.separator}MyGameClient.Target.cs"),
                DiscoveredUnrealTarget("MyGameServer", UnrealTargetType.Server, "Source${File.separator}MyGameServer.Target.cs"),
            ),
            result.targets,
        )
        assertEquals(listOf("Win64", "PS5"), result.platforms)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `discovers engine root from source checkout containing project`() {
        val engineRoot = temporaryFolder.newFolder("UnrealEngine").toPath()
        val projectRoot = engineRoot.resolve("Samples").resolve("Games").resolve("MyGame")
        createEngineToolFiles(engineRoot)
        Files.createDirectories(projectRoot.resolve("Source"))
        Files.writeString(projectRoot.resolve("MyGame.uproject"), "{}")
        Files.writeString(
            projectRoot.resolve("Source").resolve("MyGame.Target.cs"),
            """
            public class MyGameTarget : TargetRules
            {
                public MyGameTarget(TargetInfo Target) : base(Target)
                {
                    Type = TargetType.Game;
                }
            }
            """.trimIndent(),
        )

        val result = UnrealProjectDiscovery.discover(projectRoot)

        assertEquals(engineRoot.toString(), result.engineRoot)
    }

    @Test
    fun `discovers unique build environment from target files`() {
        val root = temporaryFolder.newFolder("UniqueGame").toPath()
        Files.writeString(root.resolve("UniqueGame.uproject"), "{}")
        Files.createDirectories(root.resolve("Source"))
        Files.writeString(
            root.resolve("Source").resolve("UniqueGameClient.Target.cs"),
            """
            public class UniqueGameClientTarget : TargetRules
            {
                public UniqueGameClientTarget(TargetInfo Target) : base(Target)
                {
                    Type = TargetType.Client;
                    BuildEnvironment = TargetBuildEnvironment.Unique;
                }
            }
            """.trimIndent(),
        )

        val result = UnrealProjectDiscovery.discover(root)

        assertEquals(true, result.targets.single().usesUniqueBuildEnvironment)
    }

    @Test
    fun `reports missing unreal project structure`() {
        val root = temporaryFolder.newFolder("PlainProject").toPath()

        val result = UnrealProjectDiscovery.discover(root)

        assertEquals(root.toString(), result.workspaceRoot)
        assertEquals(null, result.uprojectPath)
        assertTrue(result.targets.isEmpty())
        assertTrue(result.platforms.isNotEmpty())
        assertTrue(result.warnings.any { it.contains("No .uproject file") })
        assertTrue(result.warnings.any { it.contains("No Source directory") })
    }

    private fun createEngineToolFiles(engineRoot: Path) {
        val unrealBuildTool = engineRoot
            .resolve("Engine")
            .resolve("Binaries")
            .resolve("DotNET")
            .resolve("UnrealBuildTool")
            .resolve("UnrealBuildTool")
        val runUat = engineRoot
            .resolve("Engine")
            .resolve("Build")
            .resolve("BatchFiles")
            .resolve("RunUAT.sh")

        Files.createDirectories(unrealBuildTool.parent)
        Files.createDirectories(runUat.parent)
        Files.writeString(unrealBuildTool, "")
        Files.writeString(runUat, "")
    }
}
