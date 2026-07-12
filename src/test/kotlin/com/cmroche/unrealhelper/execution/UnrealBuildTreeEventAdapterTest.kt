package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.workflow.BuildBatch
import com.cmroche.unrealhelper.workflow.Cook
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import com.cmroche.unrealhelper.workflow.UnrealCookMode
import com.cmroche.unrealhelper.workflow.UnrealExecutionEnvironment
import com.cmroche.unrealhelper.workflow.UnrealExecutionPlan
import com.cmroche.unrealhelper.workflow.UnrealPlanPhase
import com.cmroche.unrealhelper.workflow.UnrealWorkflowRequest
import com.intellij.build.events.PresentableBuildEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class UnrealBuildTreeEventAdapterTest {
    @Test
    fun `queued actions create stable child nodes updated through running and cancelled`() {
        val artifact = UnrealArtifactKey(Path.of("/p/Lyra.uproject"), "LyraClient", "Client", "Win64", "Development")
        val build = BuildBatch(setOf(artifact))
        val cook = Cook(artifact, UnrealCookMode.FULL)
        val plan = UnrealExecutionPlan(
            UnrealWorkflowRequest.LAUNCH, "Client", "",
            UnrealExecutionEnvironment(Path.of("/e"), Path.of("/p"), Path.of("/a")),
            listOf(
                UnrealPlanPhase(build.phase, listOf(build)),
                UnrealPlanPhase(cook.phase, listOf(cook)),
            ),
        )
        val events = mutableListOf<PresentableBuildEvent>()
        val adapter = UnrealBuildTreeEventAdapter("build", plan, now = { 1L }) { event ->
            if (event is PresentableBuildEvent) events += event
        }

        adapter.queued(build)
        adapter.queued(cook)
        val waiting = events.filter { it.message.startsWith("Waiting: ") && it.parentId != "build" }
        assertEquals(2, waiting.size)
        assertTrue(waiting.all { it.parentId != "build" })

        adapter.started(build)
        val buildEvents = events.filter { it.id == waiting[0].id }
        assertEquals(listOf("Waiting", "Running"), buildEvents.map { it.message.substringBefore(':') })
        assertEquals(1, events.count { it.message.startsWith("Running: ") && it.parentId != "build" })

        adapter.finished(build, UnrealActionResult.Success)
        adapter.finished(cook, UnrealActionResult.Cancelled)
        assertEquals("Succeeded", events.last { it.id == waiting[0].id }.message.substringBefore(':'))
        assertEquals("Cancelled", events.last { it.id == waiting[1].id }.message.substringBefore(':'))
        assertEquals(0, events.count { it.message.startsWith("Running: ") && it.id == waiting[1].id })
    }
}
