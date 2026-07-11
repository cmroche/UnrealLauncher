package com.cmroche.unrealhelper.launch

import com.cmroche.unrealhelper.workflow.Launch
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class UnrealLaunchCommandBuilderTest {
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

    private fun launch(
        projectPath: Path,
        entryArguments: String,
        globalArguments: String,
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
    )
}
