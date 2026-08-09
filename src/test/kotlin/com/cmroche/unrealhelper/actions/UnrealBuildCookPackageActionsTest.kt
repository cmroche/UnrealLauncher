package com.cmroche.unrealhelper.actions

import com.cmroche.unrealhelper.config.SelectedTargetPlatformConfigurationResult
import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.config.TargetPlatformEntry
import com.cmroche.unrealhelper.execution.UnrealWorkflowConflict
import com.cmroche.unrealhelper.execution.UnrealWorkflowExecution
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.cmroche.unrealhelper.settings.UnrealTargetState
import com.cmroche.unrealhelper.workflow.UnrealExecutionPlan
import com.cmroche.unrealhelper.workflow.UnrealWorkflowPlanner
import com.cmroche.unrealhelper.workflow.UnrealWorkflowPreflightResult
import com.cmroche.unrealhelper.workflow.UnrealWorkflowRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class UnrealBuildCookPackageActionsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `build cook and package submit matching workflow requests`() {
        listOf(
            UnrealWorkflowRequest.BUILD,
            UnrealWorkflowRequest.COOK,
            UnrealWorkflowRequest.PACKAGE,
        ).forEach { request ->
            val execution = RecordingExecution()

            val error = submitter(execution).submit(
                request = request,
                configuration = configuration(),
                state = state(),
                projectBasePath = "/Workspace/Lyra",
            )

            assertNull(error)
            assertEquals(request, execution.started.single().request)
        }
    }

    @Test
    fun `validation error prevents workflow submission`() {
        val execution = RecordingExecution()
        val state = state().also { it.engineRoot = "" }

        val error = UnrealWorkflowSubmitter(
            execution = execution,
            preflight = { _, _, _, _ ->
                UnrealWorkflowPreflightResult(null, listOf("First problem", "Second problem"))
            },
        ).submit(
            request = UnrealWorkflowRequest.BUILD,
            configuration = configuration(),
            state = state,
            projectBasePath = "/Workspace/Lyra",
        )

        assertEquals(
            "Target & Platform configuration 'Client' cannot run:\nFirst problem\nSecond problem",
            error,
        )
        assertEquals(emptyList<UnrealExecutionPlan>(), execution.started)
        assertEquals(emptyList<UnrealExecutionPlan>(), execution.restarted)
    }

    @Test
    fun `incompatible platform message prevents preflight and workflow submission`() {
        val execution = RecordingExecution()
        var preflightCalled = false

        val error = UnrealWorkflowSubmitter(
            execution = execution,
            preflight = { _, _, _, _ ->
                preflightCalled = true
                UnrealWorkflowPreflightResult(null, emptyList())
            },
            platformCompatibilityErrors = { _, _ ->
                listOf(
                    "Entry 1 LyraEditor / Win64: platform 'Win64' is incompatible with target type 'Editor' " +
                        "and build configuration 'Development' in the current Rider environment",
                )
            },
        ).submit(
            request = UnrealWorkflowRequest.BUILD,
            configuration = TargetPlatformConfiguration(
                name = "Editor",
                entries = listOf(TargetPlatformEntry(targetName = "LyraEditor", platform = "Win64")),
            ),
            state = state(),
            projectBasePath = "/Workspace/Lyra",
        )

        assertEquals(
            "Target & Platform configuration 'Editor' cannot run:\n" +
                "Entry 1 LyraEditor / Win64: platform 'Win64' is incompatible with target type 'Editor' " +
                "and build configuration 'Development' in the current Rider environment",
            error,
        )
        assertEquals(false, preflightCalled)
        assertEquals(emptyList<UnrealExecutionPlan>(), execution.started)
        assertEquals(emptyList<UnrealExecutionPlan>(), execution.restarted)
    }

    @Test
    fun `submitter validates and submits the same plan exactly once when state changes`() {
        val execution = RecordingExecution()
        val state = filesystemState()
        var plannerCalls = 0
        lateinit var capturedPlan: UnrealExecutionPlan

        val error = UnrealWorkflowSubmitter(
            execution = execution,
            planner = { request, configuration, currentState, basePath ->
                plannerCalls += 1
                UnrealWorkflowPlanner().plan(request, configuration, currentState, basePath).also {
                    capturedPlan = it
                    currentState.engineRoot = currentState.workspaceRoot + "/changed-after-planning"
                }
            },
        ).submit(
            request = UnrealWorkflowRequest.BUILD,
            configuration = configuration(),
            state = state,
            projectBasePath = state.workspaceRoot,
        )

        assertNull(error)
        assertEquals(1, plannerCalls)
        assertSame(capturedPlan, execution.started.single())
    }

    @Test
    fun `invalid selected rows continue into aggregate preflight and start nothing`() {
        val execution = RecordingExecution()
        val state = filesystemState().also { it.engineRoot = "" }
        val configuration = TargetPlatformConfiguration(
            name = "Broken Client",
            entries = listOf(TargetPlatformEntry(targetName = "Missing", platform = "Android")),
        )
        val selected = SelectedTargetPlatformConfigurationResult.InvalidEntries(
            configuration = configuration,
            messages = listOf("Entry 1 Missing / Android: build target is not discovered; platform is not discovered"),
        )

        val error = submitSelectedWorkflow(selected) { selectedConfiguration ->
            UnrealWorkflowSubmitter(execution = execution).submit(
                UnrealWorkflowRequest.BUILD,
                selectedConfiguration,
                state,
                state.workspaceRoot,
            )
        }

        assertEquals(
            "Target & Platform configuration 'Broken Client' cannot run:\n" +
                "Target & Platform configuration 'Broken Client': Entry 1 Missing / Android: " +
                "build target is not discovered; platform is not discovered\n" +
                "Engine root is not configured",
            error,
        )
        assertEquals(emptyList<UnrealExecutionPlan>(), execution.started)
        assertEquals(emptyList<UnrealExecutionPlan>(), execution.restarted)
    }

    @Test
    fun `conflicting workflow is kept unless restart is explicitly confirmed`() {
        val execution = RecordingExecution(conflict = UnrealWorkflowConflict(listOf("Cook Lyra"), emptyList(), emptyList()))

        val error = submitter(execution, confirmRestart = { false }).submit(
            request = UnrealWorkflowRequest.COOK,
            configuration = configuration(),
            state = state(),
            projectBasePath = "/Workspace/Lyra",
        )

        assertNull(error)
        assertEquals(emptyList<UnrealExecutionPlan>(), execution.started)
        assertEquals(emptyList<UnrealExecutionPlan>(), execution.restarted)
    }

    @Test
    fun `explicit restart confirmation replaces conflicting workflow`() {
        val conflict = UnrealWorkflowConflict(listOf("Cook Lyra"), emptyList(), emptyList())
        val execution = RecordingExecution(conflict)

        val error = submitter(execution, confirmRestart = { true }).submit(
            request = UnrealWorkflowRequest.PACKAGE,
            configuration = configuration(),
            state = state(),
            projectBasePath = "/Workspace/Lyra",
        )

        assertNull(error)
        assertEquals(emptyList<UnrealExecutionPlan>(), execution.started)
        assertEquals(UnrealWorkflowRequest.PACKAGE, execution.restarted.single().request)
        assertEquals(conflict, execution.restartConflicts.single())
    }

    private fun configuration() = TargetPlatformConfiguration(
        name = "Client",
        entries = listOf(TargetPlatformEntry(targetName = "LyraClient", platform = "Win64")),
    )

    private fun submitter(
        execution: UnrealWorkflowExecution,
        confirmRestart: (UnrealWorkflowConflict) -> Boolean = { false },
    ) = UnrealWorkflowSubmitter(
        execution = execution,
        preflight = { request, configuration, state, basePath ->
            UnrealWorkflowPreflightResult(
                plan = UnrealWorkflowPlanner().plan(request, configuration, state, basePath),
                errors = emptyList(),
            )
        },
        confirmRestart = confirmRestart,
    )

    private fun filesystemState(): UnrealHelperSettingsState {
        val workspace = temp.newFolder().toPath()
        val engineRoot = Files.createDirectories(workspace.resolve("EngineRoot"))
        Files.createFile(workspace.resolve("Lyra.uproject"))
        val ubt = engineRoot.resolve(
            Path.of(
                "Engine",
                "Binaries",
                "DotNET",
                "UnrealBuildTool",
                if (System.getProperty("os.name").startsWith("Windows", true)) {
                    "UnrealBuildTool.exe"
                } else {
                    "UnrealBuildTool"
                },
            ),
        )
        Files.createDirectories(ubt.parent)
        Files.createFile(ubt)
        return state().also {
            it.uprojectPath = workspace.resolve("Lyra.uproject").toString()
            it.workspaceRoot = workspace.toString()
            it.engineRoot = engineRoot.toString()
        }
    }

    private fun state() = UnrealHelperSettingsState().also { state ->
        state.uprojectPath = "/Workspace/Lyra/Lyra.uproject"
        state.workspaceRoot = "/Workspace/Lyra"
        state.engineRoot = "/Engines/UE_5.6"
        state.packageDirectory = "/Artifacts/Lyra"
        state.discoveredPlatforms = mutableListOf("Win64")
        state.discoveredTargets = mutableListOf(
            UnrealTargetState().also {
                it.name = "LyraClient"
                it.type = "Client"
            },
        )
    }
}

internal class RecordingExecution(
    var conflict: UnrealWorkflowConflict? = null,
) : UnrealWorkflowExecution {
    val started = mutableListOf<UnrealExecutionPlan>()
    val restarted = mutableListOf<UnrealExecutionPlan>()
    val restartConflicts = mutableListOf<UnrealWorkflowConflict>()

    override fun conflictFor(plan: UnrealExecutionPlan): UnrealWorkflowConflict? = conflict

    override fun start(plan: UnrealExecutionPlan) {
        started += plan
    }

    override fun stopAndRestart(plan: UnrealExecutionPlan, conflict: UnrealWorkflowConflict) {
        restarted += plan
        restartConflicts += conflict
    }
}
