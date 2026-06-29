package com.cmroche.unrealhelper.config

import com.cmroche.unrealhelper.launch.QuickLaunchProfileState
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import org.junit.Assert.assertEquals
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
                    listOf(TargetPlatformEntry("Game", "Win64")),
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
    fun `legacy selected targets migrate to default shared config when file is missing`() {
        val settings = settingsWithWorkspaceRoot().also {
            it.state.selectedTargetTypes = mutableListOf("Game", "Server")
            it.state.selectedPlatforms = mutableListOf("Win64")
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
        assertEquals(listOf("Game", "Server"), configuration.entries.map { it.targetType })
        assertEquals(listOf("Win64", "Win64"), configuration.entries.map { it.platform })
        val serverEntry = configuration.entries.single { it.targetType == "Server" }
        assertEquals("-log -server", serverEntry.arguments)
        assertEquals("/tmp/MyServer.exe", serverEntry.executablePath)
        assertEquals("/tmp/Server", serverEntry.workingDirectory)
        assertEquals("Default", settings.state.selectedTargetPlatformConfigurationName)
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
}
