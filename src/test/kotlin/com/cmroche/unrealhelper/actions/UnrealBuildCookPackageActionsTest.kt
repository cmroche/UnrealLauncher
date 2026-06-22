package com.cmroche.unrealhelper.actions

import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.settings.UnrealTargetState
import org.junit.Assert.assertEquals
import org.junit.Test

class UnrealBuildCookPackageActionsTest {
    @Test
    fun `selected target type uses discovered target names with matching type`() {
        val targets = resolveUnrealCommandTargets(
            uprojectPath = "/Workspace/MyGame/MyGame.uproject",
            selectedTargetTypes = listOf("Game", "Server"),
            discoveredTargets = listOf(
                target("MyGameEditor", "Game"),
                target("MyGameClient", "Client"),
                target("MyGameServer", "Server"),
            ),
        )

        assertEquals(
            listOf(
                UnrealResolvedCommandTarget("MyGameEditor", "Game"),
                UnrealResolvedCommandTarget("MyGameServer", "Server"),
            ),
            targets,
        )
    }

    @Test
    fun `selected target type falls back to uproject basename when no discovered target matches`() {
        val targets = resolveUnrealCommandTargets(
            uprojectPath = "/Workspace/ShooterGame/ShooterGame.uproject",
            selectedTargetTypes = listOf("Client"),
            discoveredTargets = listOf(target("ShooterGameEditor", "Game")),
        )

        assertEquals(listOf(UnrealResolvedCommandTarget("ShooterGame", "Client")), targets)
    }

    @Test
    fun `command contexts include every selected target and platform pair`() {
        val settings = UnrealHelperSettings()
        settings.state.uprojectPath = "/Workspace/MyGame/MyGame.uproject"
        settings.state.workspaceRoot = "/Workspace/MyGame"
        settings.state.engineRoot = "/Engines/UE_5.6"
        settings.state.packageDirectory = "/Artifacts/MyGame"
        settings.state.buildConfiguration = "Shipping"
        settings.state.activeCommandLine = "-log"
        settings.state.selectedTargetTypes = mutableListOf("Game")
        settings.state.selectedPlatforms = mutableListOf("Win64", "Linux")
        settings.state.discoveredTargets = mutableListOf(target("MyGameEditor", "Game"))

        val contexts = createUnrealCommandContexts(settings)

        assertEquals(2, contexts.size)
        assertEquals("MyGameEditor", contexts[0].targetName)
        assertEquals("Game", contexts[0].targetType)
        assertEquals("Win64", contexts[0].platform)
        assertEquals("MyGameEditor", contexts[1].targetName)
        assertEquals("Game", contexts[1].targetType)
        assertEquals("Linux", contexts[1].platform)
        assertEquals("/Workspace/MyGame/MyGame.uproject", contexts[0].uprojectPath.toString())
        assertEquals("/Engines/UE_5.6", contexts[0].engineRoot.toString())
        assertEquals("/Workspace/MyGame", contexts[0].workspaceRoot.toString())
        assertEquals("/Artifacts/MyGame", contexts[0].packageDirectory.toString())
        assertEquals("Shipping", contexts[0].buildConfiguration)
        assertEquals("-log", contexts[0].extraArguments)
    }

    private fun target(name: String, type: String): UnrealTargetState =
        UnrealTargetState().also {
            it.name = name
            it.type = type
            it.path = "Source/$name.Target.cs"
        }
}
