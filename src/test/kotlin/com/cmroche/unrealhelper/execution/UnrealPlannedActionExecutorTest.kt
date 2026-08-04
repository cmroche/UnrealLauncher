package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.command.UnrealCommand
import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.config.TargetPlatformEntry
import com.cmroche.unrealhelper.launch.ResolvedLaunchArtifact
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.settings.UnrealTargetState
import com.cmroche.unrealhelper.workflow.BuildBatch
import com.cmroche.unrealhelper.workflow.Cook
import com.cmroche.unrealhelper.workflow.Launch
import com.cmroche.unrealhelper.workflow.Package
import com.cmroche.unrealhelper.workflow.Stage
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import com.cmroche.unrealhelper.workflow.UnrealCookMode
import com.cmroche.unrealhelper.workflow.UnrealExecutionEnvironment
import com.cmroche.unrealhelper.workflow.UnrealExecutionPlan
import com.cmroche.unrealhelper.workflow.UnrealPlannedAction
import com.cmroche.unrealhelper.workflow.UnrealWorkflowPlanner
import com.cmroche.unrealhelper.workflow.UnrealWorkflowRequest
import com.cmroche.unrealhelper.workflow.artifactDirectoryName
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutputType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class UnrealPlannedActionExecutorTest {
    @Test
    fun `queued actions use the execution environment captured by the plan`() {
        val mutableSettings = settings()
        val processes = RecordingProcessFactory()
        val executor = RiderUnrealPlannedActionExecutor(processes)
        mutableSettings.state.uprojectPath = "/Workspace/Lyra/Lyra.uproject"
        mutableSettings.state.discoveredTargets = mutableListOf(
            UnrealTargetState().also {
                it.name = "LyraClient"
                it.type = "Client"
            },
        )
        mutableSettings.state.discoveredPlatforms = mutableListOf("Win64")
        val plan = UnrealWorkflowPlanner().plan(
            UnrealWorkflowRequest.PACKAGE,
            TargetPlatformConfiguration(
                "Development",
                listOf(TargetPlatformEntry(targetName = "LyraClient", platform = "Win64")),
            ),
            mutableSettings.state,
            "/Workspace/Lyra",
        )
        val queue = UnrealExecutionQueue(executor, { NoOpPresenter() })

        queue.start(plan)
        mutableSettings.state.engineRoot = "/Engines/UE_Changed"
        mutableSettings.state.workspaceRoot = "/Workspace/Changed"
        mutableSettings.state.packageDirectory = "/Artifacts/Changed"
        processes.workflowProcesses.first().terminate(0)
        processes.workflowProcesses[1].terminate(0)
        processes.workflowProcesses[2].terminate(0)

        assertEquals("/Engines/UE_5.6/Engine/Build/BatchFiles/RunUAT.sh", processes.commands.last().executable)
        assertEquals("-project=/Workspace/Lyra/Lyra.uproject", processes.commands.last().arguments.first { it.startsWith("-project=") })
        assertEquals(
            "-archivedirectory=${plan.phases.flatMap { it.actions }.filterIsInstance<Package>().single().archiveDirectory}",
            processes.commands.last().arguments.first { it.startsWith("-archivedirectory=") },
        )
        assertEquals("/Workspace/Lyra", processes.commands.last().workingDirectory)
    }

    @Test
    fun `build cook stage and package actions map to tracked Task 5 commands`() {
        val processes = RecordingProcessFactory()
        val executor = RiderUnrealPlannedActionExecutor(processes)
        val client = artifact("LyraClient", "Client")
        val server = artifact("LyraServer", "Server")

        executor.create(BuildBatch(linkedSetOf(client, server)), environment())
        executor.create(Cook(client, UnrealCookMode.INCREMENTAL), environment())
        executor.create(Stage(server), environment())
        executor.create(Package(client), environment())

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
        assertEquals(
            "-archivedirectory=/Artifacts/Lyra/${artifactDirectoryName(client)}",
            processes.commands.last().arguments.first { it.startsWith("-archivedirectory=") },
        )
    }

    @Test
    fun `launch resolves receipt and registers a tracked Run launch command`() {
        val processes = RecordingProcessFactory()
        val executable = Path.of("/Workspace/Lyra/Binaries/Win64/LyraClient.exe")
        val executor = RiderUnrealPlannedActionExecutor(
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
            environment(),
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

    private fun environment() = UnrealExecutionEnvironment(
        engineRoot = Path.of("/Engines/UE_5.6"),
        workspaceRoot = Path.of("/Workspace/Lyra"),
        packageDirectory = Path.of("/Artifacts/Lyra"),
    )

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
        val workflowProcesses = mutableListOf<CompletableFakeProcess>()

        override fun create(command: UnrealCommand): UnrealWorkflowProcess {
            commands += command
            return CompletableFakeProcess().also(workflowProcesses::add)
        }

        override fun createLaunch(commandLine: GeneralCommandLine, title: String): UnrealWorkflowProcess {
            launchCommands += commandLine
            launchTitles += title
            return CompletableFakeProcess()
        }
    }

    private class CompletableFakeProcess : UnrealWorkflowProcess {
        private lateinit var listener: UnrealWorkflowProcessListener
        override val isProcessTerminating = false
        override var isProcessTerminated = false
        override fun start(listener: UnrealWorkflowProcessListener) {
            this.listener = listener
        }
        override fun destroy() = Unit

        fun terminate(exitCode: Int) {
            isProcessTerminated = true
            listener.terminated(exitCode)
        }
    }

    private class NoOpPresenter : UnrealWorkflowPresenter {
        override fun start(plan: UnrealExecutionPlan) = Unit
        override fun actionStarted(action: UnrealPlannedAction) = Unit
        override fun output(action: UnrealPlannedAction, text: String, type: ProcessOutputType) = Unit
        override fun actionFinished(action: UnrealPlannedAction, result: UnrealActionResult) = Unit
        override fun finish(result: UnrealPlanResult) = Unit
    }
}
