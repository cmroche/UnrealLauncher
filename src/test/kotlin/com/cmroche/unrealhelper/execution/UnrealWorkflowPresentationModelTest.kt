package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.workflow.BuildBatch
import com.cmroche.unrealhelper.workflow.Cook
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import com.cmroche.unrealhelper.workflow.UnrealCookMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class UnrealWorkflowPresentationModelTest {
    @Test
    fun `actions move from waiting to exactly one running then terminal states`() {
        val artifact = UnrealArtifactKey(Path.of("/p/Lyra.uproject"), "LyraClient", "Client", "Win64", "Development")
        val build = BuildBatch(setOf(artifact))
        val cook = Cook(artifact, UnrealCookMode.FULL)
        val model = UnrealWorkflowPresentationModel()

        model.queue(build)
        model.queue(cook)
        assertEquals(UnrealPresentationActionState.WAITING, model.stateOf(build))
        assertEquals(UnrealPresentationActionState.WAITING, model.stateOf(cook))

        model.start(build)
        assertEquals(listOf(build), model.runningActions())
        model.finish(build, UnrealActionResult.Success)
        model.start(cook)
        assertEquals(UnrealPresentationActionState.SUCCEEDED, model.stateOf(build))
        assertEquals(listOf(cook), model.runningActions())
        model.finish(cook, UnrealActionResult.Cancelled)
        assertEquals(UnrealPresentationActionState.CANCELLED, model.stateOf(cook))
        assertEquals(emptyList<Any>(), model.runningActions())
    }
}
