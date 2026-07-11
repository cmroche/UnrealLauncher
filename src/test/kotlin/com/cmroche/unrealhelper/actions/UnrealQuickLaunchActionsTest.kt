package com.cmroche.unrealhelper.actions

import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.config.TargetPlatformEntry
import com.cmroche.unrealhelper.execution.UnrealWorkflowExecution
import com.cmroche.unrealhelper.launch.QuickLaunchKey
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.cmroche.unrealhelper.settings.UnrealTargetState
import com.cmroche.unrealhelper.workflow.UnrealWorkflowRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnrealQuickLaunchActionsTest {
    @Test
    fun `launch submits a launch plan without requiring package directory`() {
        val execution = RecordingExecution()
        val state = state().also { it.packageDirectory = "" }

        val error = submitter(execution).submit(
            request = UnrealWorkflowRequest.LAUNCH,
            configuration = configuration(),
            state = state,
            projectBasePath = "/Workspace/Lyra",
        )

        assertNull(error)
        val plan = execution.started.single()
        assertEquals(UnrealWorkflowRequest.LAUNCH, plan.request)
        assertEquals(listOf("BUILD", "LAUNCH"), plan.phases.map { it.phase.name })
    }

    @Test
    fun `launch waits for workflow execution instead of resolving a command immediately`() {
        val execution = RecordingExecution()

        val error = submitter(execution).submit(
            request = UnrealWorkflowRequest.LAUNCH,
            configuration = configuration(),
            state = state(),
            projectBasePath = "/Workspace/Lyra",
        )

        assertNull(error)
        assertEquals(1, execution.started.size)
        assertEquals("LyraClient", execution.started.single().phases.last().actions.single().artifacts.single().targetName)
    }

    @Test
    fun `stop selection includes only tracked launches from selected configuration`() {
        val selected = QuickLaunchKey("Three Clients", 0, "LyraClient", "Client", "Win64")
        val other = QuickLaunchKey("Other", 0, "LyraServer", "Server", "Win64")

        val selection = stopLaunchSelection(
            selectedKeys = listOf(selected),
            runningKeys = setOf(selected, other),
        )

        assertEquals(setOf(selected), selection)
    }

    @Test
    fun `stop selection is empty when selected configuration has no tracked launches`() {
        val selected = QuickLaunchKey("Client", 0, "LyraClient", "Client", "Win64")
        val other = QuickLaunchKey("Other", 0, "LyraServer", "Server", "Win64")

        assertEquals(emptySet<QuickLaunchKey>(), stopLaunchSelection(listOf(selected), setOf(other)))
    }

    private fun configuration() = TargetPlatformConfiguration(
        name = "Client",
        entries = listOf(TargetPlatformEntry(targetName = "LyraClient", platform = "Win64")),
    )

    private fun submitter(execution: UnrealWorkflowExecution) = UnrealWorkflowSubmitter(
        execution = execution,
        preflight = { _, _, _, _ -> emptyList() },
    )

    private fun state() = UnrealHelperSettingsState().also { state ->
        state.uprojectPath = "/Workspace/Lyra/Lyra.uproject"
        state.workspaceRoot = "/Workspace/Lyra"
        state.engineRoot = "/Engines/UE_5.6"
        state.discoveredPlatforms = mutableListOf("Win64")
        state.discoveredTargets = mutableListOf(
            UnrealTargetState().also {
                it.name = "LyraClient"
                it.type = "Client"
            },
        )
    }
}
