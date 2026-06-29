package com.cmroche.unrealhelper.config

import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.cmroche.unrealhelper.settings.UnrealTargetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class TargetPlatformConfigurationValidationTest {
    @Test
    fun `duplicate configuration names are invalid`() {
        val result = validateConfigurationFile(
            TargetPlatformConfigurationsFile(
                configurations = listOf(
                    TargetPlatformConfiguration("Client"),
                    TargetPlatformConfiguration("Client"),
                ),
            ),
        )

        assertEquals(listOf("Client"), result.duplicateNames)
    }

    @Test
    fun `missing selected name asks user to select configuration`() {
        val result = resolveSelectedTargetPlatformConfiguration(
            loadResult = TargetPlatformConfigurationLoadResult.Loaded(Path.of("/Project/.unrealhelper/target-platforms.json"), TargetPlatformConfigurationsFile(), 1L),
            selectedName = "",
            state = state(),
        )

        assertTrue(result is SelectedTargetPlatformConfigurationResult.NoSelection)
    }

    @Test
    fun `stale selected name is reported so caller can clear local selection`() {
        val result = resolveSelectedTargetPlatformConfiguration(
            loadResult = TargetPlatformConfigurationLoadResult.Loaded(
                Path.of("/Project/.unrealhelper/target-platforms.json"),
                TargetPlatformConfigurationsFile(configurations = listOf(TargetPlatformConfiguration("Game Win64"))),
                1L,
            ),
            selectedName = "Deleted Config",
            state = state(),
        )

        assertTrue(result is SelectedTargetPlatformConfigurationResult.StaleSelection)
    }

    @Test
    fun `unsupported target and platform entries are reported with one based entry index`() {
        val result = resolveSelectedTargetPlatformConfiguration(
            loadResult = TargetPlatformConfigurationLoadResult.Loaded(
                Path.of("/Project/.unrealhelper/target-platforms.json"),
                TargetPlatformConfigurationsFile(
                    configurations = listOf(
                        TargetPlatformConfiguration(
                            name = "Bad Config",
                            entries = listOf(
                                TargetPlatformEntry(targetType = "Server", platform = "Android"),
                            ),
                        ),
                    ),
                ),
                1L,
            ),
            selectedName = "Bad Config",
            state = state(),
        )

        val invalid = result as SelectedTargetPlatformConfigurationResult.InvalidEntries
        assertEquals(listOf("Entry 1 Server / Android: target type is not discovered; platform is not discovered"), invalid.messages)
    }

    @Test
    fun `valid selected configuration resolves`() {
        val result = resolveSelectedTargetPlatformConfiguration(
            loadResult = TargetPlatformConfigurationLoadResult.Loaded(
                Path.of("/Project/.unrealhelper/target-platforms.json"),
                TargetPlatformConfigurationsFile(
                    configurations = listOf(
                        TargetPlatformConfiguration(
                            name = "Game Win64",
                            entries = listOf(TargetPlatformEntry(targetType = "Game", platform = "Win64")),
                        ),
                    ),
                ),
                1L,
            ),
            selectedName = "Game Win64",
            state = state(),
        )

        assertEquals("Game Win64", (result as SelectedTargetPlatformConfigurationResult.Valid).configuration.name)
    }

    private fun state(): UnrealHelperSettingsState =
        UnrealHelperSettingsState().also {
            it.discoveredTargets = mutableListOf(UnrealTargetState().also { target ->
                target.name = "MyGame"
                target.type = "Game"
            })
            it.discoveredPlatforms = mutableListOf("Win64")
        }
}
