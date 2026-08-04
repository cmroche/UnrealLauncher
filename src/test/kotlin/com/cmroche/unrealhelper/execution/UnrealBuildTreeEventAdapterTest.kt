package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.workflow.BuildBatch
import com.cmroche.unrealhelper.workflow.Cook
import com.cmroche.unrealhelper.workflow.Launch
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import com.cmroche.unrealhelper.workflow.UnrealCookMode
import com.cmroche.unrealhelper.workflow.UnrealExecutionEnvironment
import com.cmroche.unrealhelper.workflow.UnrealExecutionPlan
import com.cmroche.unrealhelper.workflow.UnrealPlanPhase
import com.cmroche.unrealhelper.workflow.UnrealPlannedAction
import com.cmroche.unrealhelper.workflow.UnrealWorkflowRequest
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FailureResult
import com.intellij.build.events.FinishEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.build.events.PresentableBuildEvent
import com.intellij.build.events.SkippedResult
import com.intellij.build.events.SuccessResult
import com.intellij.execution.process.ProcessOutputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class UnrealBuildTreeEventAdapterTest {
    @Test
    fun `adapter creates and finishes stable build tree nodes with exact results`() {
        val artifact = UnrealArtifactKey(Path.of("/p/Lyra.uproject"), "LyraClient", "Client", "Win64", "Development")
        val build = BuildBatch(setOf(artifact))
        val cook = Cook(artifact, UnrealCookMode.FULL)
        val launch = Launch(artifact, "Client", 0, "", "")
        val plan = plan(build, cook, launch)
        val events = mutableListOf<BuildEvent>()
        var time = 0L
        val adapter = UnrealBuildTreeEventAdapter("session-one", plan, now = { ++time }, emit = events::add)

        plan.phases.flatMap { it.actions }.forEach(adapter::queued)
        val waitingActions = events.filterIsInstance<PresentableBuildEvent>()
            .filter { it.message.startsWith("Waiting: ") && it.parentId != "session-one" }
        assertEquals(3, waitingActions.size)
        assertEquals(3, waitingActions.map { it.parentId }.distinct().size)
        assertEquals(
            3,
            events.filterIsInstance<PresentableBuildEvent>()
                .count { it.message.startsWith("Waiting: ") && it.parentId == "session-one" },
        )

        adapter.started(build)
        adapter.finished(build, UnrealActionResult.Success)
        adapter.started(cook)
        adapter.output(cook, "cook failed\n", ProcessOutputType.STDERR)
        adapter.finished(cook, UnrealActionResult.Failure(9, "cook failed"))
        adapter.finished(launch, UnrealActionResult.Cancelled)

        assertLifecycle(events, waitingActions[0], SuccessResult::class.java)
        assertLifecycle(events, waitingActions[1], FailureResult::class.java)
        assertLifecycle(events, waitingActions[2], SkippedResult::class.java, expectedRunning = false)

        val actionIds = waitingActions.map { it.id }.toSet()
        val phaseFinishes = events.filterIsInstance<FinishEvent>()
            .filter { it.id !in actionIds && it.parentId == "session-one" }
        assertEquals(3, phaseFinishes.size)
        assertTrue(phaseFinishes[0].result is SuccessResult)
        assertTrue(phaseFinishes[1].result is FailureResult)
        assertTrue(phaseFinishes[2].result is SkippedResult)

        val output = events.filterIsInstance<OutputBuildEvent>().single()
        assertEquals(waitingActions[1].id, output.parentId)
        assertEquals("cook failed\n", output.message)

        val secondEvents = mutableListOf<BuildEvent>()
        val second = UnrealBuildTreeEventAdapter("session-two", plan, now = { ++time }, emit = secondEvents::add)
        plan.phases.flatMap { it.actions }.forEach(second::queued)
        val secondIds = secondEvents.filterIsInstance<PresentableBuildEvent>().map { it.id }.toSet()
        assertTrue(events.filterIsInstance<PresentableBuildEvent>().map { it.id }.toSet().intersect(secondIds).isEmpty())
    }

    private fun assertLifecycle(
        events: List<BuildEvent>,
        waiting: PresentableBuildEvent,
        resultType: Class<*>,
        expectedRunning: Boolean = true,
    ) {
        val sameNode = events.filter { it.id == waiting.id }
        assertEquals(waiting, sameNode.first())
        assertEquals(expectedRunning, sameNode.filterIsInstance<PresentableBuildEvent>().any { it.message.startsWith("Running: ") })
        val finish = sameNode.filterIsInstance<FinishEvent>().single()
        assertTrue(resultType.isInstance(finish.result))
        assertTrue(finish.eventTime > waiting.eventTime)
        assertEquals(waiting.parentId, finish.parentId)
        assertEquals(sameNode.last(), finish)
    }

    private fun plan(vararg actions: UnrealPlannedAction): UnrealExecutionPlan = UnrealExecutionPlan(
        UnrealWorkflowRequest.LAUNCH,
        "Client",
        "",
        UnrealExecutionEnvironment(Path.of("/e"), Path.of("/p"), Path.of("/a")),
        actions.map { UnrealPlanPhase(it.phase, listOf(it)) },
    )
}
