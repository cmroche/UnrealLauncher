package com.cmroche.unrealhelper.actions

import com.cmroche.unrealhelper.command.UnrealCommandBuilder
import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.config.TargetPlatformEntry
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.settings.UnrealTargetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `command contexts come from selected target platform configuration`() {
        val settings = settings()
        settings.state.discoveredTargets = mutableListOf(
            target("MyGameClient", "Client"),
            target("MyGameServer", "Server"),
        )
        val configuration = TargetPlatformConfiguration(
            name = "Client and Server",
            entries = listOf(
                TargetPlatformEntry(targetType = "Client", platform = "Win64"),
                TargetPlatformEntry(targetType = "Server", platform = "Linux"),
            ),
        )

        val contexts = createUnrealCommandContexts(settings, configuration)

        assertEquals(2, contexts.size)
        assertEquals("MyGameClient", contexts[0].targetName)
        assertEquals("Client", contexts[0].targetType)
        assertEquals("Win64", contexts[0].platform)
        assertEquals("MyGameServer", contexts[1].targetName)
        assertEquals("Server", contexts[1].targetType)
        assertEquals("Linux", contexts[1].platform)
        assertEquals("/Workspace/MyGame/MyGame.uproject", contexts[0].uprojectPath.toString())
        assertEquals("/Engines/UE_5.6", contexts[0].engineRoot.toString())
        assertEquals("/Workspace/MyGame", contexts[0].workspaceRoot.toString())
        assertEquals("/Artifacts/MyGame", contexts[0].packageDirectory.toString())
        assertEquals("Shipping", contexts[0].buildConfiguration)
        assertEquals("-log", contexts[0].extraArguments)
    }

    @Test
    fun `command contexts deduplicate repeated target platform entries`() {
        val settings = settings()
        settings.state.discoveredTargets = mutableListOf(target("MyGameClient", "Client"))
        val configuration = TargetPlatformConfiguration(
            name = "Three Clients",
            entries = listOf(
                TargetPlatformEntry(targetType = "Client", platform = "Win64"),
                TargetPlatformEntry(targetType = "Client", platform = "Win64"),
                TargetPlatformEntry(targetType = "Client", platform = "Win64"),
            ),
        )

        val contexts = createUnrealCommandContexts(settings, configuration)

        assertEquals(1, contexts.size)
        assertEquals("Client", contexts.single().targetType)
        assertEquals("Win64", contexts.single().platform)
    }

    @Test
    fun `build commands are emitted for every selected target platform configuration entry`() {
        val settings = settings()
        settings.state.discoveredTargets = mutableListOf(
            target("MyGameEditor", "Game"),
            target("MyGameClient", "Client"),
        )
        val configuration = TargetPlatformConfiguration(
            name = "Build Targets",
            entries = listOf(
                TargetPlatformEntry(targetType = "Game", platform = "Win64"),
                TargetPlatformEntry(targetType = "Client", platform = "Win64"),
            ),
        )

        val commands = createUnrealCommands(
            settings = settings,
            configuration = configuration,
            commandFactory = { UnrealCommandBuilder.build(it) },
            deduplicate = false,
        )

        assertEquals(2, commands.size)
        assertEquals("Unreal Build MyGameEditor Game Win64", commands[0].title)
        assertEquals("Unreal Build MyGameClient Client Win64", commands[1].title)
    }

    @Test
    fun `cook commands are deduplicated when configuration entries generate identical command lines`() {
        val settings = settings()
        settings.state.discoveredTargets = mutableListOf(
            target("MyGameEditor", "Game"),
            target("MyGameClient", "Client"),
        )
        val configuration = TargetPlatformConfiguration(
            name = "Cook Targets",
            entries = listOf(
                TargetPlatformEntry(targetType = "Game", platform = "Win64"),
                TargetPlatformEntry(targetType = "Client", platform = "Win64"),
            ),
        )

        val commands = createUnrealCommands(
            settings = settings,
            configuration = configuration,
            commandFactory = { UnrealCommandBuilder.cook(it) },
            deduplicate = true,
        )

        assertEquals(1, commands.size)
        assertEquals("Unreal Cook MyGameEditor Game Win64", commands.single().title)
    }

    @Test
    fun `package commands are deduplicated when configuration entries generate identical command lines`() {
        val settings = settings()
        settings.state.discoveredTargets = mutableListOf(
            target("MyGameEditor", "Game"),
            target("MyGameClient", "Client"),
        )
        val configuration = TargetPlatformConfiguration(
            name = "Package Targets",
            entries = listOf(
                TargetPlatformEntry(targetType = "Game", platform = "Win64"),
                TargetPlatformEntry(targetType = "Client", platform = "Win64"),
            ),
        )

        val commands = createUnrealCommands(
            settings = settings,
            configuration = configuration,
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
        val configuration = TargetPlatformConfiguration(
            name = "Default",
            entries = listOf(TargetPlatformEntry(targetType = "Game", platform = "Win64")),
        )

        val contexts = createUnrealCommandContexts(settings, configuration, projectBasePath = "/Workspace/MyGame")

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

    @Test
    fun `missing engine root reports settings-specific validation message`() {
        val settings = settings()
        settings.state.engineRoot = ""

        assertEquals(
            "Engine root is not configured; set it in Tools > UnrealHelper before running Build, Cook, or Package.",
            buildCookPackageValidationError(settings.state, projectBasePath = "/Workspace/MyGame"),
        )
    }

    @Test
    fun `toolbar action is enabled without engine root so execution can report validation error`() {
        val settings = settings()
        settings.state.engineRoot = ""

        assertTrue(buildCookPackageActionEnabled(settings.state))
    }

    @Test
    fun `toolbar action is disabled only until uproject is configured`() {
        val settings = settings()

        settings.state.uprojectPath = ""
        assertFalse(buildCookPackageActionEnabled(settings.state))

        settings.state.uprojectPath = "/Workspace/MyGame/MyGame.uproject"
        assertTrue(buildCookPackageActionEnabled(settings.state))
    }

    private fun settings(): UnrealHelperSettings =
        UnrealHelperSettings().also {
            it.state.uprojectPath = "/Workspace/MyGame/MyGame.uproject"
            it.state.workspaceRoot = "/Workspace/MyGame"
            it.state.engineRoot = "/Engines/UE_5.6"
            it.state.packageDirectory = "/Artifacts/MyGame"
            it.state.buildConfiguration = "Shipping"
            it.state.activeCommandLine = "-log"
        }

    private fun target(name: String, type: String): UnrealTargetState =
        UnrealTargetState().also {
            it.name = name
            it.type = type
            it.path = "Source/$name.Target.cs"
        }
}
