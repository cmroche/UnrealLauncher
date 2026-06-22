package com.cmroche.unrealhelper.settings

import com.cmroche.unrealhelper.discovery.DiscoveredUnrealTarget
import com.cmroche.unrealhelper.discovery.UnrealProjectDiscoveryResult
import com.cmroche.unrealhelper.discovery.UnrealTargetType
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnrealHelperSettingsTest {
    @Test
    fun `defaults enable run debug injection`() {
        val settings = UnrealHelperSettings()

        assertEquals("", settings.state.engineRoot)
        assertEquals("Development", settings.state.buildConfiguration)
        assertEquals("", settings.state.packageDirectory)
        assertEquals("Packages", settings.effectivePackageDirectory())
        assertEquals("", settings.state.activeCommandLine)
        assertTrue(settings.state.applyToRunDebug)
        assertTrue(settings.state.savedCommandLines.isEmpty())
        assertTrue(settings.state.recentCommandLines.isEmpty())
        assertEquals(listOf("Game", "Client", "Server"), settings.state.selectedTargetTypes)
        assertTrue(settings.state.selectedPlatforms.isEmpty())
    }

    @Test
    fun `configured project requires uproject path`() {
        val settings = UnrealHelperSettings()

        assertTrue(!settings.hasConfiguredProject())

        settings.state.uprojectPath = "/Project/MyGame/MyGame.uproject"

        assertTrue(settings.hasConfiguredProject())
    }

    @Test
    fun `active command line is remembered as recent`() {
        val settings = UnrealHelperSettings()

        settings.setActiveCommandLine("-game -log")

        assertEquals("-game -log", settings.state.activeCommandLine)
        assertEquals(listOf("-game -log"), settings.state.recentCommandLines)
    }

    @Test
    fun `selected target types and platforms survive state serialization`() {
        val savedState = UnrealHelperSettingsState().also {
            it.selectedTargetTypes = mutableListOf("Game", "Server")
            it.selectedPlatforms = mutableListOf("Win64", "Linux")
        }
        val loadedState = XmlSerializer.deserialize(
            XmlSerializer.serialize(savedState),
            UnrealHelperSettingsState::class.java,
        )
        val settings = UnrealHelperSettings()

        settings.loadState(loadedState)

        assertEquals(listOf("Game", "Server"), settings.state.selectedTargetTypes)
        assertEquals(listOf("Win64", "Linux"), settings.state.selectedPlatforms)
    }

    @Test
    fun `command execution settings survive state serialization`() {
        val savedState = UnrealHelperSettingsState().also {
            it.engineRoot = "/Epic/UE_5.6"
            it.buildConfiguration = "Shipping"
            it.packageDirectory = "/Project/MyGame/Artifacts"
        }
        val loadedState = XmlSerializer.deserialize(
            XmlSerializer.serialize(savedState),
            UnrealHelperSettingsState::class.java,
        )
        val settings = UnrealHelperSettings()

        settings.loadState(loadedState)

        assertEquals("/Epic/UE_5.6", settings.state.engineRoot)
        assertEquals("Shipping", settings.state.buildConfiguration)
        assertEquals("/Project/MyGame/Artifacts", settings.state.packageDirectory)
        assertEquals("/Project/MyGame/Artifacts", settings.effectivePackageDirectory())
    }

    @Test
    fun `effective build configuration falls back to development`() {
        val settings = UnrealHelperSettings()

        assertEquals("Development", settings.effectiveBuildConfiguration())

        settings.state.buildConfiguration = ""
        assertEquals("Development", settings.effectiveBuildConfiguration())

        settings.state.buildConfiguration = "Unknown"
        assertEquals("Development", settings.effectiveBuildConfiguration())

        settings.state.buildConfiguration = "DebugGame"
        assertEquals("DebugGame", settings.effectiveBuildConfiguration())
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
