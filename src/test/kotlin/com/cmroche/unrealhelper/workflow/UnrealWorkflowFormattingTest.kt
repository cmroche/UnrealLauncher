package com.cmroche.unrealhelper.workflow

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class UnrealWorkflowFormattingTest {
    @Test
    fun `artifact descriptor includes optional architecture`() {
        assertEquals(
            "LyraClient [Client, Win64, Development, x64]",
            artifact.copy(architecture = "x64").descriptor(),
        )
    }

    @Test
    fun `planned action display names preserve existing wording`() {
        val expectedNames = listOf(
            "Build LyraClient [Client, Win64, Development]",
            "Cook LyraClient [Client, Win64, Development] (incremental)",
            "Stage LyraClient [Client, Win64, Development]",
            "Package LyraClient [Client, Win64, Development]",
            "Launch LyraClient [Client, Win64, Development] (Three Clients)",
        )
        val actions = listOf(
            BuildBatch(setOf(artifact)),
            Cook(artifact, UnrealCookMode.INCREMENTAL),
            Stage(artifact),
            Package(artifact),
            launch,
        )

        assertEquals(expectedNames, actions.map { it.displayName() })
    }

    @Test
    fun `launch title uses one based row number`() {
        assertEquals("Unreal Three Clients 2: LyraClient Client Win64", launch.launchTitle())
    }

    private companion object {
        val artifact = UnrealArtifactKey(
            projectPath = Path.of("/project/Lyra.uproject"),
            targetName = "LyraClient",
            targetType = "Client",
            platform = "Win64",
            buildConfiguration = "Development",
        )
        val launch = Launch(
            artifact = artifact,
            configurationName = "Three Clients",
            rowIndex = 1,
            entryArguments = "",
            globalArguments = "",
        )
    }
}
