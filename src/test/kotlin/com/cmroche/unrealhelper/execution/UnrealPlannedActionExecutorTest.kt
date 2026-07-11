package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.command.UnrealCommand
import com.cmroche.unrealhelper.launch.ResolvedLaunchArtifact
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.workflow.BuildBatch
import com.cmroche.unrealhelper.workflow.Cook
import com.cmroche.unrealhelper.workflow.Launch
import com.cmroche.unrealhelper.workflow.Package
import com.cmroche.unrealhelper.workflow.Stage
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import com.cmroche.unrealhelper.workflow.UnrealCookMode
import com.intellij.execution.configurations.GeneralCommandLine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class UnrealPlannedActionExecutorTest {
    @Test
    fun `build cook stage and package actions map to tracked Task 5 commands`() {
        val processes = RecordingProcessFactory()
        val executor = RiderUnrealPlannedActionExecutor(settings(), processes)
        val client = artifact("LyraClient", "Client")
        val server = artifact("LyraServer", "Server")

        executor.create(BuildBatch(linkedSetOf(client, server)))
        executor.create(Cook(client, UnrealCookMode.INCREMENTAL))
        executor.create(Stage(server))
        executor.create(Package(client))

        assertEquals(
            listOf(
                "Unreal Build 2 target(s)",
                "Unreal Cook LyraClient Client Win64",
                "Unreal Stage LyraServer Server Win64",
                "Unreal Package LyraClient Client Win64",
            ),
            processes.commands.map { it.title },
        )
        assertEquals("-cookincremental", processes.commands[1].arguments.first { it == "-cookincremental" })
        assertEquals("-archivedirectory=/Artifacts/Lyra", processes.commands.last().arguments.first { it.startsWith("-archivedirectory=") })
    }

    @Test
    fun `launch resolves receipt and registers a tracked Run launch command`() {
        val processes = RecordingProcessFactory()
        val executable = Path.of("/Workspace/Lyra/Binaries/Win64/LyraClient.exe")
        val executor = RiderUnrealPlannedActionExecutor(
            settings = settings(),
            processFactory = processes,
            receiptResolver = { key, projectRoot, engineRoot ->
                assertEquals("LyraClient", key.targetName)
                assertEquals(Path.of("/Workspace/Lyra"), projectRoot)
                assertEquals(Path.of("/Engines/UE_5.6"), engineRoot)
                ResolvedLaunchArtifact(
                    receiptPath = executable.resolveSibling("LyraClient.target"),
                    executable = executable,
                    projectPath = key.projectPath,
                    workingDirectory = executable.parent,
                    engineRoot = engineRoot,
                )
            },
        )

        executor.create(
            Launch(
                artifact = artifact("LyraClient", "Client"),
                configurationName = "Three Clients",
                rowIndex = 0,
                entryArguments = "-windowed",
                globalArguments = "-log",
            ),
        )

        assertEquals(executable.toString(), processes.launchCommands.single().exePath)
        assertEquals(listOf("-windowed", "-log"), processes.launchCommands.single().parametersList.list)
        assertEquals("Unreal Three Clients 1: LyraClient Client Win64", processes.launchTitles.single())
    }

    private fun settings() = UnrealHelperSettings().also {
        it.state.workspaceRoot = "/Workspace/Lyra"
        it.state.engineRoot = "/Engines/UE_5.6"
        it.state.packageDirectory = "/Artifacts/Lyra"
    }

    private fun artifact(name: String, type: String) = UnrealArtifactKey(
        projectPath = Path.of("/Workspace/Lyra/Lyra.uproject"),
        targetName = name,
        targetType = type,
        platform = "Win64",
        buildConfiguration = "Development",
    )

    private class RecordingProcessFactory : UnrealPlannedActionProcessFactory {
        val commands = mutableListOf<UnrealCommand>()
        val launchCommands = mutableListOf<GeneralCommandLine>()
        val launchTitles = mutableListOf<String>()

        override fun create(command: UnrealCommand): UnrealWorkflowProcess {
            commands += command
            return FakeProcess
        }

        override fun createLaunch(commandLine: GeneralCommandLine, title: String): UnrealWorkflowProcess {
            launchCommands += commandLine
            launchTitles += title
            return FakeProcess
        }
    }

    private object FakeProcess : UnrealWorkflowProcess {
        override val isProcessTerminating = false
        override val isProcessTerminated = false
        override fun start(listener: UnrealWorkflowProcessListener) = Unit
        override fun destroy() = Unit
    }
}
