package com.cmroche.unrealhelper.launch

import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class UnrealTargetReceiptTest {
    @Test
    fun `resolves a Development project receipt with engine and project macros`() {
        val roots = roots()
        val receiptPath = roots.projectRoot.resolve("Binaries/Mac/LyraEditor.target")
        writeReceipt(
            receiptPath,
            targetName = "LyraEditor",
            platform = "Mac",
            configuration = "Development",
            architecture = "arm64",
            project = "../../Lyra.uproject",
            launch = "\$(EngineDir)/Binaries/Mac/UnrealEditor.app/Contents/MacOS/UnrealEditor",
        )

        val artifact = UnrealTargetReceiptResolver.resolve(
            key = key(roots, architecture = "arm64"),
            projectRoot = roots.projectRoot,
            engineRoot = roots.engineRoot,
        )

        val executable = roots.engineRoot.resolve("Engine/Binaries/Mac/UnrealEditor.app/Contents/MacOS/UnrealEditor")
        assertEquals(receiptPath, artifact.receiptPath)
        assertEquals(executable, artifact.executable)
        assertEquals(roots.projectRoot.resolve("Lyra.uproject"), artifact.projectPath)
        assertEquals(executable.parent, artifact.workingDirectory)
    }

    @Test
    fun `matches exact receipt fields and a non-Development filename`() {
        val roots = roots()
        val receiptRoot = roots.projectRoot.resolve("Binaries/Win64")
        writeReceipt(
            receiptRoot.resolve("LyraClient.target"),
            targetName = "LyraClient",
            platform = "Win64",
            configuration = "Development",
            architecture = "x64",
            project = "../../Lyra.uproject",
            launch = "WrongDevelopment.exe",
        )
        writeReceipt(
            receiptRoot.resolve("LyraClient-Win64-Shipping-arm64.target"),
            targetName = "LyraClient",
            platform = "Win64",
            configuration = "Shipping",
            architecture = "arm64",
            project = "../../Lyra.uproject",
            launch = "LyraClient-Shipping.exe",
        )
        writeReceipt(
            receiptRoot.resolve("Other-Win64-Shipping.target"),
            targetName = "Other",
            platform = "Win64",
            configuration = "Shipping",
            architecture = "arm64",
            project = "../../Lyra.uproject",
            launch = "Other.exe",
        )

        val artifact = UnrealTargetReceiptResolver.resolve(
            key = key(
                roots = roots,
                targetName = "LyraClient",
                platform = "Win64",
                configuration = "Shipping",
                architecture = "arm64",
            ),
            projectRoot = roots.projectRoot,
            engineRoot = roots.engineRoot,
        )

        assertEquals(receiptRoot.resolve("LyraClient-Win64-Shipping-arm64.target"), artifact.receiptPath)
        assertEquals(receiptRoot.resolve("LyraClient-Shipping.exe"), artifact.executable)
    }

    @Test
    fun `accepts the sole exact receipt architecture when the artifact architecture is unknown`() {
        val roots = roots()
        val receiptPath = roots.projectRoot.resolve("Binaries/Linux/LyraServer-Linux-Shipping.target")
        writeReceipt(
            receiptPath,
            targetName = "LyraServer",
            platform = "Linux",
            configuration = "Shipping",
            architecture = "x86_64-unknown-linux-gnu",
            project = "../../Lyra.uproject",
            launch = "LyraServer",
        )

        val artifact = UnrealTargetReceiptResolver.resolve(
            key = key(
                roots = roots,
                targetName = "LyraServer",
                platform = "Linux",
                configuration = "Shipping",
                architecture = null,
            ),
            projectRoot = roots.projectRoot,
            engineRoot = roots.engineRoot,
        )

        assertEquals(receiptPath, artifact.receiptPath)
    }

    @Test
    fun `unknown architecture fails explicitly when multiple exact receipts exist`() {
        val roots = roots()
        val receiptRoot = roots.projectRoot.resolve("Binaries/Mac")
        writeReceipt(receiptRoot.resolve("LyraEditor-Mac-Development-arm64.target"), "LyraEditor", "Mac", "Development", "arm64", "../../Lyra.uproject", "arm64/Lyra")
        writeReceipt(receiptRoot.resolve("LyraEditor-Mac-Development-x64.target"), "LyraEditor", "Mac", "Development", "x64", "../../Lyra.uproject", "x64/Lyra")

        val exception = runCatching {
            UnrealTargetReceiptResolver.resolve(key(roots), roots.projectRoot, roots.engineRoot)
        }.exceptionOrNull()

        assertTrue(exception is IllegalStateException)
        assertTrue(exception?.message.orEmpty().contains("ambiguous"))
        assertTrue(exception?.message.orEmpty().contains("arm64"))
        assertTrue(exception?.message.orEmpty().contains("x64"))
        assertTrue(exception?.message.orEmpty().contains("LyraEditor [Editor, Mac, Development]"))
    }

    @Test
    fun `prefers an exact project receipt over an exact engine receipt`() {
        val roots = roots()
        val projectReceipt = roots.projectRoot.resolve("Binaries/Mac/LyraEditor.target")
        val engineReceipt = roots.engineRoot.resolve("Engine/Binaries/Mac/LyraEditor.target")
        writeReceipt(
            projectReceipt,
            targetName = "LyraEditor",
            platform = "Mac",
            configuration = "Development",
            architecture = null,
            project = "../../Lyra.uproject",
            launch = "\$(ProjectDir)/Binaries/Mac/LyraEditor",
        )
        writeReceipt(
            engineReceipt,
            targetName = "LyraEditor",
            platform = "Mac",
            configuration = "Development",
            architecture = null,
            project = null,
            launch = "\$(EngineDir)/Binaries/Mac/UnrealEditor",
        )

        val artifact = UnrealTargetReceiptResolver.resolve(
            key = key(roots),
            projectRoot = roots.projectRoot,
            engineRoot = roots.engineRoot,
        )

        assertEquals(projectReceipt, artifact.receiptPath)
        assertEquals(roots.projectRoot.resolve("Binaries/Mac/LyraEditor"), artifact.executable)
    }

    @Test
    fun `reports both receipt search roots when no exact receipt exists`() {
        val roots = roots()
        val projectSearchRoot = Files.createDirectories(roots.projectRoot.resolve("Binaries/Mac"))
        val engineSearchRoot = Files.createDirectories(roots.engineRoot.resolve("Engine/Binaries/Mac"))
        writeReceipt(
            projectSearchRoot.resolve("LyraEditor.target"),
            targetName = "LyraEditor",
            platform = "Mac",
            configuration = "DebugGame",
            architecture = null,
            project = "../../Lyra.uproject",
            launch = "LyraEditor",
        )

        val exception = runCatching {
            UnrealTargetReceiptResolver.resolve(key(roots), roots.projectRoot, roots.engineRoot)
        }.exceptionOrNull()

        assertTrue(exception is IllegalStateException)
        assertTrue(exception?.message.orEmpty().contains(projectSearchRoot.toString()))
        assertTrue(exception?.message.orEmpty().contains(engineSearchRoot.toString()))
        assertTrue(exception?.message.orEmpty().contains("LyraEditor"))
    }

    private fun roots(): TestRoots {
        val root = Files.createTempDirectory("unreal-target-receipt")
        val projectRoot = Files.createDirectories(root.resolve("Lyra"))
        val engineRoot = Files.createDirectories(root.resolve("UnrealEngine"))
        Files.createFile(projectRoot.resolve("Lyra.uproject"))
        return TestRoots(projectRoot, engineRoot)
    }

    private fun key(
        roots: TestRoots,
        targetName: String = "LyraEditor",
        platform: String = "Mac",
        configuration: String = "Development",
        architecture: String? = null,
    ): UnrealArtifactKey = UnrealArtifactKey(
        projectPath = roots.projectRoot.resolve("Lyra.uproject"),
        targetName = targetName,
        targetType = targetName.removePrefix("Lyra"),
        platform = platform,
        buildConfiguration = configuration,
        architecture = architecture,
    )

    private fun writeReceipt(
        path: Path,
        targetName: String,
        platform: String,
        configuration: String,
        architecture: String?,
        project: String?,
        launch: String,
    ) {
        Files.createDirectories(path.parent)
        val architectureProperty = architecture?.let { "\"Architecture\": \"$it\"," }.orEmpty()
        val projectProperty = project?.let { "\"Project\": \"$it\"," }.orEmpty()
        Files.writeString(
            path,
            """
            {
              "TargetName": "$targetName",
              "Platform": "$platform",
              "Configuration": "$configuration",
              "BuildSettingsVersion": "V7",
              "TargetType": "Game",
              $architectureProperty
              $projectProperty
              "Launch": "$launch",
              "BuildProducts": [{"Path": "$launch", "Type": "Executable"}]
            }
            """.trimIndent(),
        )
    }

    private data class TestRoots(val projectRoot: Path, val engineRoot: Path)
}
