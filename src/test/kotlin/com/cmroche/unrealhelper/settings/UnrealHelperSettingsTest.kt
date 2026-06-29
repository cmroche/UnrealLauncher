package com.cmroche.unrealhelper.settings

import com.cmroche.unrealhelper.discovery.DiscoveredUnrealTarget
import com.cmroche.unrealhelper.discovery.UnrealProjectDiscoveryResult
import com.cmroche.unrealhelper.discovery.UnrealTargetType
import com.cmroche.unrealhelper.launch.QuickLaunchProfileState
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.lang.reflect.Proxy
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.border.TitledBorder

class UnrealHelperSettingsTest {
    @Test
    fun `configurable component omits legacy target platform editors`() {
        val settings = UnrealHelperSettings().also {
            it.state.selectedTargetTypes = mutableListOf("Game")
            it.state.selectedPlatforms = mutableListOf("Win64")
            it.state.quickLaunchProfiles = mutableListOf(
                QuickLaunchProfileState(
                    targetType = "Game",
                    platform = "Win64",
                    executablePath = "/Project/MyGame.exe",
                ),
            )
        }
        val configurable = UnrealHelperConfigurable(projectWithSettings(settings))

        val labels = componentTexts(configurable.createComponent())

        assertTrue(labels.contains("Project"))
        assertTrue(labels.contains("Detection"))
        assertTrue(labels.contains("Global Run/Debug Args"))
        assertFalse(labels.contains("Targets And Platforms"))
        assertFalse(labels.contains("Target Types:"))
        assertFalse(labels.contains("Platforms:"))
        assertFalse(labels.contains("Quick Launch"))
    }

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
    fun `selected target platform configuration name survives state serialization`() {
        val savedState = UnrealHelperSettingsState().also {
            it.selectedTargetPlatformConfigurationName = "Client and Server"
        }
        val loadedState = XmlSerializer.deserialize(
            XmlSerializer.serialize(savedState),
            UnrealHelperSettingsState::class.java,
        )
        val settings = UnrealHelperSettings()

        settings.loadState(loadedState)

        assertEquals("Client and Server", settings.state.selectedTargetPlatformConfigurationName)
    }

    @Test
    fun `quick launch profiles survive state serialization`() {
        val savedState = UnrealHelperSettingsState().also {
            it.quickLaunchProfiles = mutableListOf(
                QuickLaunchProfileState(
                    name = "Game Win64",
                    targetType = "Game",
                    platform = "Win64",
                    executablePath = "/Project/MyGame/Binaries/Win64/MyGame.exe",
                    workingDirectory = "/Project/MyGame",
                    arguments = "-log",
                ),
            )
        }
        val loadedState = XmlSerializer.deserialize(
            XmlSerializer.serialize(savedState),
            UnrealHelperSettingsState::class.java,
        )
        val settings = UnrealHelperSettings()

        settings.loadState(loadedState)

        val profile = settings.state.quickLaunchProfiles.single()
        assertEquals("Game Win64", profile.name)
        assertEquals("Game", profile.targetType)
        assertEquals("Win64", profile.platform)
        assertEquals("/Project/MyGame/Binaries/Win64/MyGame.exe", profile.executablePath)
        assertEquals("/Project/MyGame", profile.workingDirectory)
        assertEquals("-log", profile.arguments)
    }

    @Test
    fun `profile helper returns existing profile or creates default for target platform pair`() {
        val state = UnrealHelperSettingsState()

        val createdProfile = state.profileFor("Server", "Linux")
        val existingProfile = state.profileFor("Server", "Linux")

        assertSame(createdProfile, existingProfile)
        assertEquals("Server Linux", createdProfile.name)
        assertEquals("Server", createdProfile.targetType)
        assertEquals("Linux", createdProfile.platform)
        assertEquals(listOf(createdProfile), state.quickLaunchProfiles)
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
                engineRoot = null,
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
    fun `discovery initializes engine root when it is blank`() {
        val settings = UnrealHelperSettings()

        settings.applyDiscoveryResult(
            UnrealProjectDiscoveryResult(
                workspaceRoot = "/Project/Engine/Samples/Games/MyGame",
                uprojectPath = "/Project/Engine/Samples/Games/MyGame/MyGame.uproject",
                engineRoot = "/Project/Engine",
                targets = emptyList(),
                platforms = emptyList(),
                warnings = emptyList(),
            ),
        )

        assertEquals("/Project/Engine", settings.state.engineRoot)
    }

    @Test
    fun `discovery keeps configured engine root`() {
        val settings = UnrealHelperSettings()
        settings.state.engineRoot = "/Custom/Engine"

        settings.applyDiscoveryResult(
            UnrealProjectDiscoveryResult(
                workspaceRoot = "/Project/Engine/Samples/Games/MyGame",
                uprojectPath = "/Project/Engine/Samples/Games/MyGame/MyGame.uproject",
                engineRoot = "/Project/Engine",
                targets = emptyList(),
                platforms = emptyList(),
                warnings = emptyList(),
            ),
        )

        assertEquals("/Custom/Engine", settings.state.engineRoot)
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
                engineRoot = null,
                targets = emptyList(),
                platforms = listOf("Win64", "PS5"),
                warnings = emptyList(),
            ),
        )

        assertEquals("/Custom/Packages", settings.state.packageDirectory)
        assertEquals(listOf("Win64"), settings.state.selectedPlatforms)
    }

    private fun projectWithSettings(settings: UnrealHelperSettings): Project {
        val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
            when (method.name) {
                "getService", "getServiceIfCreated" ->
                    if (args?.firstOrNull() == UnrealHelperSettings::class.java) settings else null
                "isDisposed" -> false
                "getName" -> "TestProject"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "TestProject"
                else -> defaultReturnValue(method.returnType)
            }
        }
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
            handler,
        ) as Project
    }

    private fun defaultReturnValue(returnType: Class<*>): Any? =
        when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> 0.toChar()
            else -> null
        }

    private fun componentTexts(component: Component): List<String> {
        val texts = mutableListOf<String>()
        ((component as? JComponent)?.border as? TitledBorder)?.title?.let(texts::add)
        when (component) {
            is JLabel -> component.text?.let(texts::add)
            is AbstractButton -> component.text?.let(texts::add)
        }
        if (component is Container) {
            component.components.flatMapTo(texts) { componentTexts(it) }
        }
        return texts
    }
}
