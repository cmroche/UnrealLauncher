package com.cmroche.unrealhelper.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class UnrealProjectDiscoveryTest {
    @JvmField
    @Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `uses Rider project target and platform data`() {
        val root = temporaryFolder.newFolder("MyGame").toPath()
        val projectFile = Files.writeString(root.resolve("MyGame.uproject"), "{}")
        val source = Files.createDirectories(root.resolve("Source"))
        val gameTarget = writeTarget(
            source.resolve("MyGame.Target.cs"),
            "MyGameTarget",
            "TargetRules",
            "Game",
        )
        val clientTarget = writeTarget(
            source.resolve("MyGameClient.Target.cs"),
            "MyGameClientTarget",
            "TargetRules",
            "Client",
        )

        val result = UnrealProjectDiscovery.fromRiderModel(
            uprojectPath = projectFile,
            engineRoot = root.parent.resolve("UnrealEngine"),
            targetFiles = listOf(gameTarget, clientTarget),
            platforms = listOf("Win64", "Mac", "Win64"),
        )

        assertEquals(root.toString(), result.workspaceRoot)
        assertEquals(projectFile.toString(), result.uprojectPath)
        assertEquals(root.parent.resolve("UnrealEngine").toString(), result.engineRoot)
        assertEquals(
            listOf(
                DiscoveredUnrealTarget("MyGame", UnrealTargetType.Game),
                DiscoveredUnrealTarget("MyGameClient", UnrealTargetType.Client),
            ),
            result.targets,
        )
        assertEquals(listOf("Mac", "Win64"), result.platforms)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `resolves Rider logical target locations from the uproject directory`() {
        val projectFile = Path.of("/Projects/Lyra/Lyra.uproject")

        assertEquals(
            Path.of("/Projects/Lyra/Source/LyraGame.Target.cs"),
            resolveRiderTargetPath(
                projectFile,
                "/Projects/Lyra/LyraGame.Target.cs in <Lyra>/Source",
            ),
        )
        assertEquals(
            Path.of("/Projects/Lyra/Source/LyraGame.Target.cs"),
            resolveRiderTargetPath(projectFile, "Source/LyraGame.Target.cs"),
        )
    }

    @Test
    fun `does not discover target files Rider did not return`() {
        val root = temporaryFolder.newFolder("ScopedGame").toPath()
        val projectFile = Files.writeString(root.resolve("ScopedGame.uproject"), "{}")
        val source = Files.createDirectories(root.resolve("Source"))
        val returnedTarget = writeTarget(
            source.resolve("ScopedGame.Target.cs"),
            "ScopedGameTarget",
            "TargetRules",
            "Game",
        )
        writeTarget(
            source.resolve("IgnoredServer.Target.cs"),
            "IgnoredServerTarget",
            "TargetRules",
            "Server",
        )

        val targets = UnrealProjectDiscovery.fromRiderModel(
            uprojectPath = projectFile,
            engineRoot = null,
            targetFiles = listOf(returnedTarget),
            platforms = listOf("Win64"),
        ).targets

        assertEquals(listOf("ScopedGame"), targets.map { it.name })
    }

    @Test
    fun `inherits target type from Rider supplied base target`() {
        val root = temporaryFolder.newFolder("InheritedGame").toPath()
        val projectFile = Files.writeString(root.resolve("InheritedGame.uproject"), "{}")
        val source = Files.createDirectories(root.resolve("Source"))
        val serverTarget = writeTarget(
            source.resolve("InheritedServer.Target.cs"),
            "InheritedServerTarget",
            "TargetRules",
            "Server",
        )
        val variantTarget = writeTarget(
            source.resolve("InheritedServerEOS.Target.cs"),
            "InheritedServerEOSTarget",
            "InheritedServerTarget",
            null,
        )

        val target = UnrealProjectDiscovery.fromRiderModel(
            uprojectPath = projectFile,
            engineRoot = null,
            targetFiles = listOf(serverTarget, variantTarget),
            platforms = listOf("Win64"),
        ).targets.single { it.name == "InheritedServerEOS" }

        assertEquals(UnrealTargetType.Server, target.type)
    }

    @Test
    fun `reports Rider model gaps and unreadable returned targets`() {
        val root = temporaryFolder.newFolder("IncompleteGame").toPath()
        val missingTarget = root.resolve("Source").resolve("Missing.Target.cs")

        val result = UnrealProjectDiscovery.fromRiderModel(
            uprojectPath = null,
            engineRoot = null,
            targetFiles = listOf(missingTarget),
            platforms = emptyList(),
        )

        assertEquals(null, result.workspaceRoot)
        assertTrue(result.targets.isEmpty())
        assertTrue(result.warnings.any { it.contains("did not provide an Unreal project file") })
        assertTrue(result.warnings.any { it.contains("did not provide any Unreal target files") })
        assertTrue(result.warnings.any { it.contains("did not provide any Unreal target platforms") })
    }

    @Test
    fun `reports a returned target file that cannot be read`() {
        val root = temporaryFolder.newFolder("MissingTargetGame").toPath()
        val projectFile = Files.writeString(root.resolve("MissingTargetGame.uproject"), "{}")
        val missingTarget = root.resolve("Source").resolve("Missing.Target.cs")

        val result = UnrealProjectDiscovery.fromRiderModel(
            uprojectPath = projectFile,
            engineRoot = null,
            targetFiles = listOf(missingTarget),
            platforms = listOf("Win64"),
        )

        assertTrue(result.targets.isEmpty())
        assertTrue(result.warnings.any { it.contains("Could not read Rider target file") })
    }

    private fun writeTarget(
        path: java.nio.file.Path,
        className: String,
        baseClassName: String,
        targetType: String?,
    ): java.nio.file.Path {
        val typeAssignment = targetType?.let { "Type = TargetType.$it;" }.orEmpty()
        return Files.writeString(
            path,
            """
            public class $className : $baseClassName
            {
                public $className(TargetInfo Target) : base(Target)
                {
                    $typeAssignment
                }
            }
            """.trimIndent(),
        )
    }
}
