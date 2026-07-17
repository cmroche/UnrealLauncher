package com.cmroche.unrealhelper.discovery

import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UnrealProjectStartupActivityTest {
    @Test
    fun `startup activity uses the ProjectActivity extension point`() {
        val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()

        assertTrue(
            pluginXml.contains(
                "<postStartupActivity implementation=\"com.cmroche.unrealhelper.discovery.UnrealProjectStartupActivity\"/>",
            ),
        )
        assertFalse(
            pluginXml.contains(
                "<backgroundPostStartupActivity implementation=\"com.cmroche.unrealhelper.discovery.UnrealProjectStartupActivity\"/>",
            ),
        )
    }

    @Test
    fun `startup discovery runs for unconfigured project`() {
        val settings = UnrealHelperSettings()

        assertTrue(shouldRefreshProjectOnStartup(settings))
    }

    @Test
    fun `startup discovery runs for configured project missing engine root`() {
        val settings = UnrealHelperSettings()
        settings.state.uprojectPath = "/Project/Lyra/Lyra.uproject"

        assertTrue(shouldRefreshProjectOnStartup(settings))
    }

    @Test
    fun `startup discovery does not overwrite fully configured project`() {
        val settings = UnrealHelperSettings()
        settings.state.uprojectPath = "/Project/Lyra/Lyra.uproject"
        settings.state.engineRoot = "/Project/UnrealEngine"

        assertFalse(shouldRefreshProjectOnStartup(settings))
    }
}
