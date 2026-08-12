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
    fun `blank configuration names are invalid`() {
        val result = validateConfigurationFile(
            TargetPlatformConfigurationsFile(
                configurations = listOf(TargetPlatformConfiguration("")),
            ),
        )

        assertEquals(1, result.blankNameCount)
    }

    @Test
    fun `missing selected name asks user to select configuration`() {
        val result = resolveSelectedTargetPlatformConfiguration(
            loadResult = TargetPlatformConfigurationLoadResult.Loaded(
                Path.of("/Project/.unrealhelper/target-platforms.json"),
                TargetPlatformConfigurationsFile(),
            ),
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
            ),
            selectedName = "Deleted Config",
            state = state(),
        )

        assertTrue(result is SelectedTargetPlatformConfigurationResult.StaleSelection)
    }

    @Test
    fun `missing exact build target is invalid`() {
        assertInvalid(
            entry = TargetPlatformEntry(targetName = "MissingTarget", platform = "Win64"),
            expectedMessage = "Entry 1 MissingTarget / Win64: build target is not discovered",
        )
    }

    @Test
    fun `incremental cook without cook is invalid`() {
        assertInvalid(
            entry = TargetPlatformEntry(
                targetName = "LyraClient",
                platform = "Win64",
                cookOnLaunch = false,
                incrementalCookOnLaunch = true,
            ),
            expectedMessage = "Entry 1 LyraClient / Win64: incremental cook requires Cook",
        )
    }

    @Test
    fun `missing platform retains one based entry index`() {
        val result = selectedConfigurationResult(
            entries = listOf(
                TargetPlatformEntry(targetName = "LyraClient", platform = "Win64"),
                TargetPlatformEntry(targetName = "LyraClient", platform = "Android"),
            ),
        )

        val invalid = result as SelectedTargetPlatformConfigurationResult.InvalidEntries
        assertEquals(listOf("Entry 2 LyraClient / Android: platform is not discovered"), invalid.messages)
    }

    @Test
    fun `exact build target resolves with inferred target type`() {
        val result = resolveConfigurationEntries(
            configuration = TargetPlatformConfiguration(
                name = "Client Win64",
                entries = listOf(
                    TargetPlatformEntry(
                        targetName = "LyraClient",
                        platform = "Win64",
                        arguments = "-log",
                        cookOnLaunch = true,
                        incrementalCookOnLaunch = true,
                    ),
                ),
            ),
            state = state(),
        )

        assertEquals(emptyList<String>(), result.messages)
        assertEquals(
            listOf(
                ResolvedTargetPlatformEntry(
                    index = 0,
                    targetName = "LyraClient",
                    targetType = "Client",
                    platform = "Win64",
                    arguments = "-log",
                    cookOnLaunch = true,
                    incrementalCookOnLaunch = true,
                ),
            ),
            result.entries,
        )
    }

    @Test
    fun `all invalid row messages are returned together`() {
        val result = selectedConfigurationResult(
            entries = listOf(
                TargetPlatformEntry(targetName = "MissingTarget", platform = "Win64"),
                TargetPlatformEntry(
                    targetName = "LyraClient",
                    platform = "Android",
                    incrementalCookOnLaunch = true,
                ),
            ),
        ) as SelectedTargetPlatformConfigurationResult.InvalidEntries

        assertEquals(
            listOf(
                "Entry 1 MissingTarget / Win64: build target is not discovered",
                "Entry 2 LyraClient / Android: platform is not discovered; incremental cook requires Cook",
            ),
            result.messages,
        )
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
                            entries = listOf(TargetPlatformEntry(targetName = "LyraClient", platform = "Win64")),
                        ),
                    ),
                ),
            ),
            selectedName = "Game Win64",
            state = state(),
        )

        assertEquals("Game Win64", (result as SelectedTargetPlatformConfigurationResult.Valid).configuration.name)
    }

    private fun state(): UnrealHelperSettingsState =
        UnrealHelperSettingsState().also {
            it.discoveredTargets = mutableListOf(UnrealTargetState().also { target ->
                target.name = "LyraClient"
                target.type = "Client"
            })
            it.discoveredPlatforms = mutableListOf("Win64")
        }

    private fun assertInvalid(entry: TargetPlatformEntry, expectedMessage: String) {
        val result = selectedConfigurationResult(listOf(entry))
        val invalid = result as SelectedTargetPlatformConfigurationResult.InvalidEntries
        assertEquals(listOf(expectedMessage), invalid.messages)
    }

    private fun selectedConfigurationResult(
        entries: List<TargetPlatformEntry>,
    ): SelectedTargetPlatformConfigurationResult =
        resolveSelectedTargetPlatformConfiguration(
            loadResult = TargetPlatformConfigurationLoadResult.Loaded(
                Path.of("/Project/.unrealhelper/target-platforms.json"),
                TargetPlatformConfigurationsFile(
                    configurations = listOf(TargetPlatformConfiguration("Test Config", entries)),
                ),
            ),
            selectedName = "Test Config",
            state = state(),
        )
}
