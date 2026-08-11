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
import java.nio.file.Files
import java.nio.file.Path

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
    fun `save publishes cached configuration without reading file again`() {
        val settings = settingsWithWorkspaceRoot()
        val storage = CountingStorage(TargetPlatformConfigurationStore())
        val service = TargetPlatformConfigurationService.createForTest(settings, storage)
        val file = TargetPlatformConfigurationsFile(
            configurations = listOf(TargetPlatformConfiguration("Game")),
        )

        service.save(file)

        assertEquals(0, storage.loadCount)
        assertEquals(1, storage.saveCount)
        assertEquals(
            file,
            (service.load() as TargetPlatformConfigurationLoadResult.Loaded).file,
        )
    }

    @Test
    fun `cached reads and selection resolution do not reload configuration file`() {
        val settings = settingsWithWorkspaceRoot().also {
            it.state.selectedTargetPlatformConfigurationName = "Client"
            it.state.discoveredPlatforms = mutableListOf("Win64")
            it.state.discoveredTargets = mutableListOf(target("LyraClient", "Client"))
        }
        val file = TargetPlatformConfigurationsFile(
            configurations = listOf(
                TargetPlatformConfiguration(
                    name = "Client",
                    entries = listOf(TargetPlatformEntry(targetName = "LyraClient", platform = "Win64")),
                ),
            ),
        )
        val delegate = TargetPlatformConfigurationStore().also { store ->
            store.save(requireNotNull(configurationPath(settings)), file)
        }
        val storage = CountingStorage(delegate)
        val service = TargetPlatformConfigurationService.createForTest(settings, storage)
        service.migrateLegacySelectionIfNeeded()

        repeat(20) {
            assertTrue(service.load() is TargetPlatformConfigurationLoadResult.Loaded)
            assertTrue(service.selectedConfigurationResult() is SelectedTargetPlatformConfigurationResult.Valid)
        }

        assertEquals(1, storage.loadCount)
    }

    @Test
    fun `configuration change scope includes exact file directory and atomic save files`() {
        val configurationPath = temp.root.toPath()
            .resolve(".unrealhelper")
            .resolve("target-platforms.json")

        assertTrue(
            TargetPlatformConfigurationService.pathAffectsConfiguration(
                configurationPath.toString(),
                configurationPath,
            ),
        )
        assertTrue(
            TargetPlatformConfigurationService.pathAffectsConfiguration(
                configurationPath.parent.resolve("target-platforms.json.123.tmp").toString(),
                configurationPath,
            ),
        )
        assertTrue(
            TargetPlatformConfigurationService.pathAffectsConfiguration(
                configurationPath.parent.toString(),
                configurationPath,
            ),
        )
        assertFalse(
            TargetPlatformConfigurationService.pathAffectsConfiguration(
                configurationPath.parent.resolveSibling(".idea").resolve("workspace.xml").toString(),
                configurationPath,
            ),
        )
        assertFalse(
            TargetPlatformConfigurationService.pathAffectsConfiguration(
                configurationPath.parent.resolveSibling(".unrealhelper-other").resolve("file.json").toString(),
                configurationPath,
            ),
        )
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
    fun `legacy migration is not repeated after the first resolved path load`() {
        val settings = settingsWithWorkspaceRoot().also {
            it.state.selectedTargetTypes = mutableListOf("Game")
            it.state.selectedPlatforms = mutableListOf("Win64")
            it.state.discoveredTargets = mutableListOf(target("LyraGame", "Game"))
        }
        val service = TargetPlatformConfigurationService.createForTest(settings, TargetPlatformConfigurationStore())

        service.migrateLegacySelectionIfNeeded()
        val path = requireNotNull(service.configurationPath())
        assertTrue(Files.exists(path))
        Files.delete(path)

        service.migrateLegacySelectionIfNeeded()

        assertFalse(Files.exists(path))
        assertTrue(service.load() is TargetPlatformConfigurationLoadResult.Missing)
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

    private fun configurationPath(settings: UnrealHelperSettings): Path? =
        TargetPlatformConfigurationService.createForTest(
            settings,
            TargetPlatformConfigurationStore(),
        ).configurationPath()

    private fun target(name: String, type: String): UnrealTargetState =
        UnrealTargetState().also {
            it.name = name
            it.type = type
        }

    private class CountingStorage(
        private val delegate: TargetPlatformConfigurationStorage,
    ) : TargetPlatformConfigurationStorage {
        var loadCount: Int = 0
            private set
        var saveCount: Int = 0
            private set

        override fun load(
            path: Path,
            discoveredTargets: List<UnrealTargetState>,
        ): TargetPlatformConfigurationLoadResult {
            loadCount += 1
            return delegate.load(path, discoveredTargets)
        }

        override fun save(
            path: Path,
            file: TargetPlatformConfigurationsFile,
        ): TargetPlatformConfigurationLoadResult.Loaded {
            saveCount += 1
            return delegate.save(path, file)
        }
    }
}
