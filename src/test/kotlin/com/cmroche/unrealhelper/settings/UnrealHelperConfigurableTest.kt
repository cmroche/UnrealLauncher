package com.cmroche.unrealhelper.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class UnrealHelperConfigurableTest {
    @Test
    fun `display values expose effective diagnostic defaults`() {
        val settings = UnrealHelperSettings().also {
            it.state.uprojectPath = "/Project/MyGame/MyGame.uproject"
            it.state.workspaceRoot = "/Project/MyGame"
            it.state.engineRoot = "/Engine"
            it.state.packageDirectory = ""
            it.state.buildConfiguration = "Unsupported"
            it.state.activeCommandLine = "-game -log"
        }

        assertEquals(
            SettingsDisplayValues(
                uprojectPath = "/Project/MyGame/MyGame.uproject",
                workspaceRoot = "/Project/MyGame",
                packageDirectory = "/Project/MyGame/Packages",
                engineRoot = "/Engine",
                buildConfiguration = UnrealHelperSettings.DefaultBuildConfiguration,
            ),
            settingsDisplayValues(settings),
        )
    }
}
