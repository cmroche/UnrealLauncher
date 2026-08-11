package com.cmroche.unrealhelper.run

import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.cmroche.unrealhelper.settings.UnrealTargetState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnrealRunConfigurationMatcherTest {
    @Test
    fun `settings are not injectable when disabled`() {
        val state = unrealState()
        state.applyToRunDebug = false

        assertFalse(UnrealRunConfigurationMatcher.hasInjectionSettings(state))
    }

    @Test
    fun `settings are not injectable without active args`() {
        val state = unrealState()
        state.activeCommandLine = ""

        assertFalse(UnrealRunConfigurationMatcher.hasInjectionSettings(state))
    }

    @Test
    fun `configuration compatibility does not require active args`() {
        val state = unrealState()
        state.activeCommandLine = ""

        assertTrue(
            UnrealRunConfigurationMatcher.isLikelyUnrealRunConfiguration(
                data = matchData(configurationName = "MyGame"),
                state = state,
            ),
        )
    }

    @Test
    fun `matches project name from uproject path without discovered targets`() {
        val state = UnrealHelperSettingsState().also {
            it.uprojectPath = "/Project/Lyra/Lyra.uproject"
        }

        assertTrue(
            UnrealRunConfigurationMatcher.isLikelyUnrealRunConfiguration(
                data = matchData(configurationName = "Lyra", executablePath = null, workingDirectory = null),
                state = state,
            ),
        )
    }

    @Test
    fun `configuration compatibility requires unreal project context`() {
        val state = unrealState()
        state.uprojectPath = ""
        state.discoveredTargets = mutableListOf()

        assertFalse(UnrealRunConfigurationMatcher.hasUnrealProjectContext(state))
        assertFalse(
            UnrealRunConfigurationMatcher.isLikelyUnrealRunConfiguration(
                data = matchData(configurationName = "MyGame"),
                state = state,
            ),
        )
    }

    @Test
    fun `matches configuration that references uproject path`() {
        assertTrue(
            UnrealRunConfigurationMatcher.isLikelyUnrealRunConfiguration(
                data = matchData(arguments = listOf("/Project/MyGame.uproject", "-game")),
                state = unrealState(),
            ),
        )
    }

    @Test
    fun `matches discovered target name`() {
        assertTrue(
            UnrealRunConfigurationMatcher.isLikelyUnrealRunConfiguration(
                data = matchData(configurationName = "MyGameServer"),
                state = unrealState(),
            ),
        )
    }

    @Test
    fun `does not match unrelated cidr configuration in unreal project`() {
        assertFalse(
            UnrealRunConfigurationMatcher.isLikelyUnrealRunConfiguration(
                data = matchData(
                    configurationName = "UnitTests",
                    executablePath = "/Project/MyGame/build/tests/UnitTests",
                    workingDirectory = "/Project/MyGame",
                ),
                state = unrealState(),
            ),
        )
    }

    private fun unrealState(): UnrealHelperSettingsState =
        UnrealHelperSettingsState().also {
            it.uprojectPath = "/Project/MyGame/MyGame.uproject"
            it.activeCommandLine = "-game -log"
            it.discoveredTargets = mutableListOf(
                UnrealTargetState().also { target ->
                    target.name = "MyGame"
                    target.type = "Game"
                },
                UnrealTargetState().also { target ->
                    target.name = "MyGameServer"
                    target.type = "Server"
                },
            )
        }

    private fun matchData(
        configurationName: String = "Run",
        configurationTypeId: String = "CidrRunConfiguration",
        factoryId: String = "Cidr",
        executablePath: String? = "/Project/MyGame/Binaries/Win64/MyGame.exe",
        workingDirectory: String? = "/Project/MyGame",
        arguments: List<String> = emptyList(),
    ): RunConfigurationMatchData =
        RunConfigurationMatchData(
            configurationName = configurationName,
            configurationTypeId = configurationTypeId,
            factoryId = factoryId,
            executablePath = executablePath,
            workingDirectory = workingDirectory,
            arguments = arguments,
        )
}
