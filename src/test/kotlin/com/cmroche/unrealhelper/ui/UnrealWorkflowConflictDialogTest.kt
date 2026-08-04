package com.cmroche.unrealhelper.ui

import com.cmroche.unrealhelper.execution.UnrealWorkflowConflict
import com.cmroche.unrealhelper.launch.QuickLaunchInstanceId
import com.cmroche.unrealhelper.launch.QuickLaunchKey
import com.cmroche.unrealhelper.launch.RunningLaunchInfo
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class UnrealWorkflowConflictDialogTest {
    @Test
    fun `message formats running queued and launched process sections`() {
        val key = QuickLaunchKey("Three Clients", 0, "LyraClient", "Client", "Win64")
        val conflict = UnrealWorkflowConflict(
            runningActions = listOf("Cook LyraClient Client Win64"),
            queuedActions = listOf("Launch LyraClient Client Win64"),
            launchedProcesses = listOf(
                RunningLaunchInfo(
                    key = key,
                    artifact = UnrealArtifactKey(
                        projectPath = Path.of("/Workspace/Lyra/Lyra.uproject"),
                        targetName = "LyraClient",
                        targetType = "Client",
                        platform = "Win64",
                        buildConfiguration = "Development",
                    ),
                    title = "Unreal Three Clients 1",
                    instanceId = QuickLaunchInstanceId(1),
                ),
            ),
        )

        val text = conflictMessage(conflict)

        assertTrue(text.contains("Running:\n- Cook LyraClient Client Win64"))
        assertTrue(text.contains("Queued:\n- Launch LyraClient Client Win64"))
        assertTrue(text.contains("Launched Processes:\n- Unreal Three Clients 1"))
    }

    @Test
    fun `empty sections are omitted`() {
        val text = conflictMessage(UnrealWorkflowConflict(emptyList(), listOf("Build Lyra"), emptyList()))

        assertTrue(!text.contains("Running:"))
        assertTrue(text.contains("Queued:\n- Build Lyra"))
        assertTrue(!text.contains("Launched Processes:"))
    }
}
