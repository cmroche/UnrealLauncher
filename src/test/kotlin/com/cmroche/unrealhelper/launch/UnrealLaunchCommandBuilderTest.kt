package com.cmroche.unrealhelper.launch

import com.cmroche.unrealhelper.workflow.Launch
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class UnrealLaunchCommandBuilderTest {
    @Test
    fun `cooked launch uses its loose cook sandbox`() {
        val sandbox = Path.of("/Workspace/Lyra/Saved/UnrealHelper/Cooked/LyraClient/WindowsClient")
        val command = UnrealLaunchCommandBuilder.build(
            launch(cookedSandbox = sandbox),
            artifact(),
        )

        assertTrue(command.parametersList.parameters.contains("-sandbox=$sandbox"))
    }

    @Test
    fun `uncooked launch has no sandbox argument`() {
        val command = UnrealLaunchCommandBuilder.build(launch(), artifact())
        assertFalse(command.parametersList.parameters.any { it.startsWith("-sandbox=") })
    }
    @Test
    fun `engine launch passes project before entry and global arguments`() {
        val engineRoot = Path.of("/Workspace/UnrealEngine")
        val projectPath = Path.of("/Workspace/Lyra/Lyra.uproject")
        val executable = engineRoot.resolve("Engine/Binaries/Mac/UnrealEditor")
        val artifact = ResolvedLaunchArtifact(
            receiptPath = Path.of("/Workspace/Lyra/Binaries/Mac/LyraEditor.target"),
            executable = executable,
            projectPath = projectPath,
            workingDirectory = executable.parent,
            engineRoot = engineRoot,
        )

        val command = UnrealLaunchCommandBuilder.build(
            action = launch(
                projectPath = projectPath,
                entryArguments = "-game -ExecCmds=\"stat fps\"",
                globalArguments = "-log \"-trace=cpu,gpu\"",
            ),
            artifact = artifact,
        )

        assertEquals(executable.toString(), command.exePath)
        assertEquals(executable.parent, command.workingDirectory)
        assertEquals(
            listOf(
                projectPath.toString(),
                "-game",
                "-ExecCmds=stat fps",
                "-log",
                "-trace=cpu,gpu",
            ),
            command.parametersList.list,
        )
    }

    @Test
    fun `project executable omits project argument`() {
        val engineRoot = Path.of("/Workspace/UnrealEngine")
        val projectPath = Path.of("/Workspace/Lyra/Lyra.uproject")
        val executable = Path.of("/Workspace/Lyra/Binaries/Linux/LyraServer")
        val artifact = ResolvedLaunchArtifact(
            receiptPath = Path.of("/Workspace/Lyra/Binaries/Linux/LyraServer.target"),
            executable = executable,
            projectPath = projectPath,
            workingDirectory = executable.parent,
            engineRoot = engineRoot,
        )

        val command = UnrealLaunchCommandBuilder.build(
            action = launch(
                projectPath = projectPath,
                entryArguments = "-server",
                globalArguments = "-log",
            ),
            artifact = artifact,
        )

        assertEquals(listOf("-server", "-log"), command.parametersList.list)
    }

    @Test
    fun `project executable under engine checkout omits project argument`() {
        val engineRoot = Path.of("/Workspace/UnrealEngine")
        val projectRoot = engineRoot.resolve("Samples/Games/Lyra")
        val projectPath = projectRoot.resolve("Lyra.uproject")
        val executable = projectRoot.resolve("Binaries/Mac/Lyra")
        val artifact = ResolvedLaunchArtifact(
            receiptPath = projectRoot.resolve("Binaries/Mac/Lyra.target"),
            executable = executable,
            projectPath = projectPath,
            workingDirectory = executable.parent,
            engineRoot = engineRoot,
        )

        val command = UnrealLaunchCommandBuilder.build(
            action = launch(
                projectPath = projectPath,
                entryArguments = "-game",
                globalArguments = "-log",
            ),
            artifact = artifact,
        )

        assertEquals(listOf("-game", "-log"), command.parametersList.list)
    }

    private fun artifact(): ResolvedLaunchArtifact {
        val projectPath = Path.of("/Workspace/Lyra/Lyra.uproject")
        val engineRoot = Path.of("/Workspace/UnrealEngine")
        val executable = Path.of("/Workspace/Lyra/Binaries/Win64/LyraClient.exe")
        return ResolvedLaunchArtifact(Path.of("/receipt.target"), executable, projectPath, executable.parent, engineRoot)
    }

    private fun launch(
        projectPath: Path = Path.of("/Workspace/Lyra/Lyra.uproject"),
        entryArguments: String = "",
        globalArguments: String = "",
        cookedSandbox: Path? = null,
    ): Launch = Launch(
        artifact = UnrealArtifactKey(
            projectPath = projectPath,
            targetName = "LyraEditor",
            targetType = "Editor",
            platform = "Mac",
            buildConfiguration = "Development",
        ),
        configurationName = "Desktop",
        rowIndex = 0,
        entryArguments = entryArguments,
        globalArguments = globalArguments,
        cookedSandbox = cookedSandbox,
    )
}
