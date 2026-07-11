package com.cmroche.unrealhelper.config

import com.cmroche.unrealhelper.launch.QuickLaunchProfileState
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.settings.UnrealTargetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TargetPlatformConfigurationServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `configuration path uses workspace root`() {
        val workspaceRoot = temp.root.toPath()
        val settings = UnrealHelperSettings().also {
            it.state.workspaceRoot = workspaceRoot.toString()
        }
        val service = TargetPlatformConfigurationService.createForTest(settings, TargetPlatformConfigurationStore())

        assertEquals(
            workspaceRoot.resolve(".unrealhelper").resolve("target-platforms.json"),
            service.configurationPath(),
        )
    }

    @Test
    fun `configuration path falls back to uproject parent`() {
        val projectRoot = temp.newFolder("Project").toPath()
        val settings = UnrealHelperSettings().also {
            it.state.uprojectPath = projectRoot.resolve("MyGame.uproject").toString()
        }
        val service = TargetPlatformConfigurationService.createForTest(settings, TargetPlatformConfigurationStore())

        assertEquals(
            projectRoot.resolve(".unrealhelper").resolve("target-platforms.json"),
            service.configurationPath(),
        )
    }

    @Test
    fun `save writes shared configuration file`() {
        val settings = settingsWithWorkspaceRoot()
        val service = TargetPlatformConfigurationService.createForTest(settings, TargetPlatformConfigurationStore())
        val file = TargetPlatformConfigurationsFile(
            configurations = listOf(
                TargetPlatformConfiguration(
                    "Game Win64",
                    listOf(TargetPlatformEntry(targetName = "MyGame", platform = "Win64")),
                ),
            ),
        )

        service.save(file)

        val result = service.load()
        assertTrue(result is TargetPlatformConfigurationLoadResult.Loaded)
        result as TargetPlatformConfigurationLoadResult.Loaded
        assertEquals(file, result.file)
    }

    @Test
    fun `managed save discards compatibility path fields`() {
        val settings = settingsWithWorkspaceRoot()
        val workspaceRoot = temp.root.toPath()
        val executable = workspaceRoot.resolve("Packages/Windows/MyGame.exe")
        val workingDirectory = workspaceRoot.resolve("Saved/Launch")
        val service = TargetPlatformConfigurationService.createForTest(settings, TargetPlatformConfigurationStore())

        service.saveManagedConfigurations(
            TargetPlatformConfigurationsFile(
                configurations = listOf(
                    TargetPlatformConfiguration(
                        "Game Win64",
                        listOf(
                            TargetPlatformEntry(
                                targetType = "Game",
                                platform = "Win64",
                                executablePath = executable.toString(),
                                workingDirectory = workingDirectory.toString(),
                            ),
                        ),
                    ),
                ),
            ),
            dialogSelectedName = "Game Win64",
        )

        val result = service.load()
        assertTrue(result is TargetPlatformConfigurationLoadResult.Loaded)
        result as TargetPlatformConfigurationLoadResult.Loaded
        val entry = result.file.configurations.single().entries.single()
        assertEquals("", entry.executablePath)
        assertEquals("", entry.workingDirectory)
    }

    @Test
    fun `legacy selected targets migrate to default shared config when file is missing`() {
        val settings = settingsWithWorkspaceRoot().also {
            it.state.selectedTargetTypes = mutableListOf("Game", "Server")
            it.state.selectedPlatforms = mutableListOf("Win64")
            it.state.discoveredTargets = mutableListOf(
                target("LyraGame", "Game"),
                target("LyraServer", "Server"),
            )
            it.state.quickLaunchProfiles = mutableListOf(
                QuickLaunchProfileState(
                    targetType = "Server",
                    platform = "Win64",
                    arguments = "-log -server",
                    executablePath = "/tmp/MyServer.exe",
                    workingDirectory = "/tmp/Server",
                ),
            )
        }
        val service = TargetPlatformConfigurationService.createForTest(settings, TargetPlatformConfigurationStore())

        service.migrateLegacySelectionIfNeeded()

        val result = service.load()
        assertTrue(result is TargetPlatformConfigurationLoadResult.Loaded)
        result as TargetPlatformConfigurationLoadResult.Loaded
        val configuration = result.file.configurations.single()
        assertEquals("Default", configuration.name)
        assertEquals(listOf("LyraGame", "LyraServer"), configuration.entries.map { it.targetName })
        assertEquals(listOf("Win64", "Win64"), configuration.entries.map { it.platform })
        val serverEntry = configuration.entries.single { it.targetName == "LyraServer" }
        assertEquals("-log -server", serverEntry.arguments)
        assertEquals("", serverEntry.executablePath)
        assertEquals("", serverEntry.workingDirectory)
        assertFalse(serverEntry.cookOnLaunch)
        assertFalse(serverEntry.incrementalCookOnLaunch)
        assertEquals("Default", settings.state.selectedTargetPlatformConfigurationName)
    }

    @Test
    fun `management load migrates legacy selected targets before opening dialog`() {
        val settings = settingsWithWorkspaceRoot().also {
            it.state.selectedTargetTypes = mutableListOf("Game")
            it.state.selectedPlatforms = mutableListOf("Win64")
            it.state.discoveredTargets = mutableListOf(target("LyraGame", "Game"))
        }
        val service = TargetPlatformConfigurationService.createForTest(settings, TargetPlatformConfigurationStore())

        val result = service.loadForManagement()

        assertTrue(result is TargetPlatformConfigurationLoadResult.Loaded)
        result as TargetPlatformConfigurationLoadResult.Loaded
        assertEquals("Default", result.file.configurations.single().name)
        assertEquals("Default", settings.state.selectedTargetPlatformConfigurationName)
    }

    @Test
    fun `managed save preserves renamed selected configuration`() {
        val settings = settingsWithWorkspaceRoot().also {
            it.state.selectedTargetPlatformConfigurationName = "Old"
        }
        val service = TargetPlatformConfigurationService.createForTest(settings, TargetPlatformConfigurationStore())
        service.save(TargetPlatformConfigurationsFile(configurations = listOf(TargetPlatformConfiguration("Old"))))

        service.saveManagedConfigurations(
            TargetPlatformConfigurationsFile(configurations = listOf(TargetPlatformConfiguration("New"))),
            dialogSelectedName = "New",
        )

        assertEquals("New", settings.state.selectedTargetPlatformConfigurationName)
    }

    @Test
    fun `managed save follows dialog selection when selected configuration is deleted`() {
        val settings = settingsWithWorkspaceRoot().also {
            it.state.selectedTargetPlatformConfigurationName = "Old"
        }
        val service = TargetPlatformConfigurationService.createForTest(settings, TargetPlatformConfigurationStore())
        service.save(
            TargetPlatformConfigurationsFile(
                configurations = listOf(
                    TargetPlatformConfiguration("Old"),
                    TargetPlatformConfiguration("Next"),
                ),
            ),
        )

        service.saveManagedConfigurations(
            TargetPlatformConfigurationsFile(configurations = listOf(TargetPlatformConfiguration("Next"))),
            dialogSelectedName = "Next",
        )

        assertEquals("Next", settings.state.selectedTargetPlatformConfigurationName)
    }

    @Test
    fun `managed save keeps no selection when no configuration was previously selected`() {
        val settings = settingsWithWorkspaceRoot()
        val service = TargetPlatformConfigurationService.createForTest(settings, TargetPlatformConfigurationStore())

        service.saveManagedConfigurations(
            TargetPlatformConfigurationsFile(configurations = listOf(TargetPlatformConfiguration("Game"))),
            dialogSelectedName = "Game",
        )

        assertEquals("", settings.state.selectedTargetPlatformConfigurationName)
    }

    @Test
    fun `stale selected config is cleared`() {
        val settings = settingsWithWorkspaceRoot().also {
            it.state.selectedTargetPlatformConfigurationName = "Removed"
        }
        val service = TargetPlatformConfigurationService.createForTest(settings, TargetPlatformConfigurationStore())
        service.save(
            TargetPlatformConfigurationsFile(
                configurations = listOf(
                    TargetPlatformConfiguration(
                        "Current",
                        listOf(TargetPlatformEntry("Game", "Win64")),
                    ),
                ),
            ),
        )

        service.clearStaleSelectionIfNeeded()

        assertEquals("", settings.state.selectedTargetPlatformConfigurationName)
    }

    private fun settingsWithWorkspaceRoot(): UnrealHelperSettings {
        val settings = UnrealHelperSettings()
        settings.state.workspaceRoot = temp.root.toPath().toString()
        return settings
    }

    private fun target(name: String, type: String): UnrealTargetState =
        UnrealTargetState().also {
            it.name = name
            it.type = type
        }
}
