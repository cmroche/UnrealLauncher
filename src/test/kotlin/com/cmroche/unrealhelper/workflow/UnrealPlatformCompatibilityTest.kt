package com.cmroche.unrealhelper.workflow

import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.config.TargetPlatformEntry
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.cmroche.unrealhelper.settings.UnrealTargetState
import org.junit.Assert.assertEquals
import org.junit.Test

class UnrealPlatformCompatibilityTest {
    @Test
    fun `editor platform is compatible when Rider exposes its tuple`() {
        assertEquals(
            emptyList<String>(),
            incompatiblePlatformErrors(
                configuration = configuration("LyraEditor", "Mac"),
                state = state("LyraEditor", "Editor", "Mac"),
                availableConfigurations = listOf(UnrealIdeConfigurationAndPlatform("Development Editor", "Mac")),
            ),
        )
    }

    @Test
    fun `missing Rider tuple reports incompatible platform`() {
        assertEquals(
            listOf(
                "Entry 1 LyraEditor / Win64: platform 'Win64' is incompatible with target type 'Editor' " +
                    "and build configuration 'Development' in the current Rider environment",
            ),
            incompatiblePlatformErrors(
                configuration = configuration("LyraEditor", "Win64"),
                state = state("LyraEditor", "Editor", "Win64"),
                availableConfigurations = listOf(UnrealIdeConfigurationAndPlatform("Development Editor", "Mac")),
            ),
        )
    }

    @Test
    fun `game uses Rider configuration without a target type suffix`() {
        assertEquals(
            emptyList<String>(),
            incompatiblePlatformErrors(
                configuration = configuration("Lyra", "Mac"),
                state = state("Lyra", "Game", "Mac"),
                availableConfigurations = listOf(UnrealIdeConfigurationAndPlatform("Development", "Mac-arm64")),
            ),
        )
    }

    @Test
    fun `unavailable Rider model leaves compatibility unknown`() {
        val configuration = configuration("LyraEditor", "Win64")
        val state = state("LyraEditor", "Editor", "Win64")

        assertEquals(emptyList<String>(), incompatiblePlatformErrors(configuration, state, null))
        assertEquals(emptyList<String>(), incompatiblePlatformErrors(configuration, state, emptyList()))
    }

    private fun configuration(targetName: String, platform: String) = TargetPlatformConfiguration(
        name = "Test",
        entries = listOf(TargetPlatformEntry(targetName = targetName, platform = platform)),
    )

    private fun state(targetName: String, targetType: String, platform: String) =
        UnrealHelperSettingsState().also { state ->
            state.buildConfiguration = "Development"
            state.discoveredPlatforms = mutableListOf(platform)
            state.discoveredTargets = mutableListOf(
                UnrealTargetState().also {
                    it.name = targetName
                    it.type = targetType
                },
            )
        }
}
