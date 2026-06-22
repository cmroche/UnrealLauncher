package com.cmroche.unrealhelper.actions

import com.cmroche.unrealhelper.command.UnrealCommandBuilder
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
        val settings = settings()
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

    @Test
    fun `build commands are emitted for every selected target type on one platform`() {
        val settings = settings()
        settings.state.selectedTargetTypes = mutableListOf("Game", "Client")
        settings.state.selectedPlatforms = mutableListOf("Win64")
        settings.state.discoveredTargets = mutableListOf(
            target("MyGameEditor", "Game"),
            target("MyGameClient", "Client"),
        )

        val commands = createUnrealCommands(
            settings = settings,
            commandFactory = { UnrealCommandBuilder.build(it) },
            deduplicate = false,
        )

        assertEquals(2, commands.size)
        assertEquals("Unreal Build MyGameEditor Game Win64", commands[0].title)
        assertEquals("Unreal Build MyGameClient Client Win64", commands[1].title)
    }

    @Test
    fun `cook commands are deduplicated when selected target types generate identical command lines`() {
        val settings = settings()
        settings.state.selectedTargetTypes = mutableListOf("Game", "Client")
        settings.state.selectedPlatforms = mutableListOf("Win64")
        settings.state.discoveredTargets = mutableListOf(
            target("MyGameEditor", "Game"),
            target("MyGameClient", "Client"),
        )

        val commands = createUnrealCommands(
            settings = settings,
            commandFactory = { UnrealCommandBuilder.cook(it) },
            deduplicate = true,
        )

        assertEquals(1, commands.size)
        assertEquals("Unreal Cook MyGameEditor Game Win64", commands.single().title)
    }

    @Test
    fun `package commands are deduplicated when selected target types generate identical command lines`() {
        val settings = settings()
        settings.state.selectedTargetTypes = mutableListOf("Game", "Client")
        settings.state.selectedPlatforms = mutableListOf("Win64")
        settings.state.discoveredTargets = mutableListOf(
            target("MyGameEditor", "Game"),
            target("MyGameClient", "Client"),
        )

        val commands = createUnrealCommands(
            settings = settings,
            commandFactory = { UnrealCommandBuilder.packageProject(it) },
            deduplicate = true,
        )

        assertEquals(1, commands.size)
        assertEquals("Unreal Package MyGameEditor Game Win64", commands.single().title)
    }

    @Test
    fun `basename-only uproject path uses project base path as workspace fallback`() {
        val settings = settings()
        settings.state.uprojectPath = "MyGame.uproject"
        settings.state.workspaceRoot = ""

        val contexts = createUnrealCommandContexts(settings, projectBasePath = "/Workspace/MyGame")

        assertEquals("/Workspace/MyGame", contexts.single().workspaceRoot.toString())
    }

    @Test
    fun `basename-only uproject path without project base path reports missing workspace root`() {
        val settings = settings()
        settings.state.uprojectPath = "MyGame.uproject"
        settings.state.workspaceRoot = ""

        assertEquals(
            "Workspace root is not configured",
            buildCookPackageValidationError(settings.state, projectBasePath = null),
        )
    }

    private fun settings(): UnrealHelperSettings =
        UnrealHelperSettings().also {
            it.state.uprojectPath = "/Workspace/MyGame/MyGame.uproject"
            it.state.workspaceRoot = "/Workspace/MyGame"
            it.state.engineRoot = "/Engines/UE_5.6"
            it.state.packageDirectory = "/Artifacts/MyGame"
            it.state.buildConfiguration = "Shipping"
            it.state.activeCommandLine = "-log"
            it.state.selectedTargetTypes = mutableListOf("Game")
            it.state.selectedPlatforms = mutableListOf("Win64")
        }

    private fun target(name: String, type: String): UnrealTargetState =
        UnrealTargetState().also {
            it.name = name
            it.type = type
            it.path = "Source/$name.Target.cs"
        }
}
