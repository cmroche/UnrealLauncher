package com.cmroche.unrealhelper.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UnrealHelperConfigurableTest {
    @Test
    fun `form values expose effective defaults and editor command line`() {
        val settings = UnrealHelperSettings().also {
            it.state.uprojectPath = "/Project/MyGame/MyGame.uproject"
            it.state.workspaceRoot = "/Project/MyGame"
            it.state.engineRoot = "/Engine"
            it.state.packageDirectory = ""
            it.state.buildConfiguration = "Unsupported"
            it.state.activeCommandLine = "-game -log"
            it.state.applyToRunDebug = false
        }

        assertEquals(
            SettingsFormValues(
                uprojectPath = "/Project/MyGame/MyGame.uproject",
                workspaceRoot = "/Project/MyGame",
                packageDirectory = "/Project/MyGame/Packages",
                engineRoot = "/Engine",
                buildConfiguration = UnrealHelperSettings.DefaultBuildConfiguration,
                commandLine = "-game\n-log",
                applyToRunDebug = false,
            ),
            settingsFormValues(settings),
        )
    }

    @Test
    fun `applying form values normalizes persisted settings`() {
        val settings = UnrealHelperSettings()

        applySettingsFormValues(
            settings,
            SettingsFormValues(
                uprojectPath = " /Project/MyGame/MyGame.uproject ",
                workspaceRoot = " /Project/MyGame ",
                packageDirectory = " /Project/MyGame/Artifacts ",
                engineRoot = " /Engine ",
                buildConfiguration = "Unsupported",
                commandLine = "-game\n-log",
                applyToRunDebug = false,
            ),
        )

        assertEquals("/Project/MyGame/MyGame.uproject", settings.state.uprojectPath)
        assertEquals("/Project/MyGame", settings.state.workspaceRoot)
        assertEquals("/Project/MyGame/Artifacts", settings.state.packageDirectory)
        assertEquals("/Engine", settings.state.engineRoot)
        assertEquals(UnrealHelperSettings.DefaultBuildConfiguration, settings.state.buildConfiguration)
        assertEquals("-game -log", settings.state.activeCommandLine)
        assertEquals(listOf("-game -log"), settings.state.recentCommandLines)
        assertFalse(settings.state.applyToRunDebug)
    }
}
