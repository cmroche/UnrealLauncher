package com.cmroche.unrealhelper.settings

import com.cmroche.unrealhelper.discovery.DiscoveredUnrealTarget
import com.cmroche.unrealhelper.discovery.UnrealProjectDiscoveryResult
import com.cmroche.unrealhelper.discovery.UnrealTargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnrealHelperSettingsTest {
    @Test
    fun `defaults enable run debug injection`() {
        val settings = UnrealHelperSettings()

        assertEquals("", settings.state.activeCommandLine)
        assertTrue(settings.state.applyToRunDebug)
        assertTrue(settings.state.savedCommandLines.isEmpty())
        assertTrue(settings.state.recentCommandLines.isEmpty())
        assertEquals(listOf("Game", "Client", "Server"), settings.state.selectedTargetTypes)
        assertTrue(settings.state.selectedPlatforms.isEmpty())
    }

    @Test
    fun `active command line is remembered as recent`() {
        val settings = UnrealHelperSettings()

        settings.setActiveCommandLine("-game -log")

        assertEquals("-game -log", settings.state.activeCommandLine)
        assertEquals(listOf("-game -log"), settings.state.recentCommandLines)
    }

    @Test
    fun `saved command lines are unique and newest first`() {
        val settings = UnrealHelperSettings()

        settings.saveCommandLine("-game")
        settings.saveCommandLine("-server")
        settings.saveCommandLine("-game")

        assertEquals(listOf("-game", "-server"), settings.state.savedCommandLines)
    }

    @Test
    fun `discovery result updates project state and initializes platform selection`() {
        val settings = UnrealHelperSettings()

        settings.applyDiscoveryResult(
            UnrealProjectDiscoveryResult(
                workspaceRoot = "/Project/MyGame",
                uprojectPath = "/Project/MyGame/MyGame.uproject",
                targets = listOf(
                    DiscoveredUnrealTarget("MyGame", UnrealTargetType.Game, "Source/MyGame.Target.cs"),
                    DiscoveredUnrealTarget("MyGameServer", UnrealTargetType.Server, "Source/MyGameServer.Target.cs"),
                ),
                platforms = listOf("Win64", "PS5"),
                warnings = listOf("Example warning"),
            ),
        )

        assertEquals("/Project/MyGame", settings.state.workspaceRoot)
        assertEquals("/Project/MyGame/MyGame.uproject", settings.state.uprojectPath)
        assertEquals("/Project/MyGame/Packages", settings.state.packageDirectory)
        assertEquals(listOf("Win64", "PS5"), settings.state.selectedPlatforms)
        assertEquals(listOf("Win64", "PS5"), settings.state.discoveredPlatforms)
        assertEquals(listOf("Example warning"), settings.state.discoveryWarnings)
        assertEquals("MyGameServer", settings.state.discoveredTargets[1].name)
        assertEquals("Server", settings.state.discoveredTargets[1].type)
    }

    @Test
    fun `discovery keeps existing package directory and selected platforms`() {
        val settings = UnrealHelperSettings()
        settings.state.packageDirectory = "/Custom/Packages"
        settings.state.selectedPlatforms = mutableListOf("Win64")

        settings.applyDiscoveryResult(
            UnrealProjectDiscoveryResult(
                workspaceRoot = "/Project/MyGame",
                uprojectPath = "/Project/MyGame/MyGame.uproject",
                targets = emptyList(),
                platforms = listOf("Win64", "PS5"),
                warnings = emptyList(),
            ),
        )

        assertEquals("/Custom/Packages", settings.state.packageDirectory)
        assertEquals(listOf("Win64"), settings.state.selectedPlatforms)
    }
}
