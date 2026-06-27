package com.cmroche.unrealhelper.discovery

import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnrealProjectStartupActivityTest {
    @Test
    fun `startup discovery runs for unconfigured project`() {
        val settings = UnrealHelperSettings()

        assertTrue(shouldRefreshProjectOnStartup(settings))
    }

    @Test
    fun `startup discovery does not overwrite configured project`() {
        val settings = UnrealHelperSettings()
        settings.state.uprojectPath = "/Project/Lyra/Lyra.uproject"

        assertFalse(shouldRefreshProjectOnStartup(settings))
    }
}
