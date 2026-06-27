package com.cmroche.unrealhelper.ui

import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalArgsToolbarActionTest {
    @Test
    fun `toolbar editor value updates active command line before opening expanded editor`() {
        val settings = UnrealHelperSettings()
        settings.setActiveCommandLine("-noop")

        val commandLine = syncToolbarCommandLine(settings, "-test -noop")

        assertEquals("-test -noop", commandLine)
        assertEquals("-test -noop", settings.state.activeCommandLine)
    }

    @Test
    fun `toolbar editor sync does not remember partial typed command lines`() {
        val settings = UnrealHelperSettings()

        syncToolbarCommandLine(settings, "-")

        assertTrue(settings.knownCommandLines().isEmpty())
    }

    @Test
    fun `missing toolbar editor value keeps current active command line`() {
        val settings = UnrealHelperSettings()
        settings.setActiveCommandLine("-noop")

        val commandLine = syncToolbarCommandLine(settings, null)

        assertEquals("-noop", commandLine)
        assertEquals("-noop", settings.state.activeCommandLine)
    }
}
