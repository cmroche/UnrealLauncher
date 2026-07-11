package com.cmroche.unrealhelper.actions

import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.config.TargetPlatformEntry
import com.cmroche.unrealhelper.execution.UnrealWorkflowConflict
import com.cmroche.unrealhelper.execution.UnrealWorkflowExecution
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.cmroche.unrealhelper.settings.UnrealTargetState
import com.cmroche.unrealhelper.workflow.UnrealExecutionPlan
import com.cmroche.unrealhelper.workflow.UnrealWorkflowRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnrealBuildCookPackageActionsTest {
    @Test
    fun `build cook and package submit matching workflow requests`() {
        listOf(
            UnrealWorkflowRequest.BUILD,
            UnrealWorkflowRequest.COOK,
            UnrealWorkflowRequest.PACKAGE,
        ).forEach { request ->
            val execution = RecordingExecution()

            val error = UnrealWorkflowSubmitter(execution).submit(
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

        val error = UnrealWorkflowSubmitter(execution).submit(
            request = UnrealWorkflowRequest.BUILD,
            configuration = configuration(),
            state = state,
            projectBasePath = "/Workspace/Lyra",
        )

        assertEquals(
            "Engine root is not configured; set it in Tools > UnrealHelper before running Build, Cook, or Package.",
            error,
        )
        assertEquals(emptyList<UnrealExecutionPlan>(), execution.started)
        assertEquals(emptyList<UnrealExecutionPlan>(), execution.restarted)
    }

    @Test
    fun `conflicting workflow is kept unless restart is explicitly confirmed`() {
        val execution = RecordingExecution(conflict = UnrealWorkflowConflict(listOf("Cook Lyra"), emptyList(), emptyList()))

        val error = UnrealWorkflowSubmitter(execution, confirmRestart = { false }).submit(
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

        val error = UnrealWorkflowSubmitter(execution, confirmRestart = { true }).submit(
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
