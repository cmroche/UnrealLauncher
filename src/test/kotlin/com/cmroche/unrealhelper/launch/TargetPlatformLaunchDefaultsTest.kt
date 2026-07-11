package com.cmroche.unrealhelper.launch

import com.cmroche.unrealhelper.config.TargetPlatformEntry
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.cmroche.unrealhelper.settings.UnrealTargetState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TargetPlatformLaunchDefaultsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `derived defaults use unique target executable name and project-relative paths`() {
        val workspaceRoot = temp.root.toPath()
        val state = UnrealHelperSettingsState().also {
            it.workspaceRoot = workspaceRoot.toString()
            it.uprojectPath = workspaceRoot.resolve("MyGame.uproject").toString()
            it.discoveredTargets = mutableListOf(
                UnrealTargetState().also { target ->
                    target.name = "MyGameClient"
                    target.type = "Client"
                    target.usesUniqueBuildEnvironment = true
                },
            )
        }

        val entry = TargetPlatformEntry(targetType = "Client", platform = "Win64")
            .withDerivedLaunchPaths(state, workspaceRoot.resolve("Packages"))

        assertEquals("Packages/Windows/MyGameClient.exe", entry.executablePath)
        assertEquals("Packages/Windows", entry.workingDirectory)
    }
}
