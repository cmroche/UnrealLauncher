package com.cmroche.unrealhelper.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnrealHelperSettingsTest {
    @Test
    fun `defaults enable run debug injection`() {
        val settings = UnrealHelperSettings()

        assertEquals("", settings.state.activeCommandLine)
        assertTrue(settings.state.applyToRunDebug)
        assertTrue(settings.state.savedCommandLines.isEmpty())
        assertTrue(settings.state.recentCommandLines.isEmpty())
    }

    @Test
    fun `active command line is remembered as recent`() {
        val settings = UnrealHelperSettings()

        settings.setActiveCommandLine("-game -log")

        assertEquals("-game -log", settings.state.activeCommandLine)
        assertEquals(listOf("-game -log"), settings.state.recentCommandLines)
    }

    @Test
    fun `saved command lines are unique and newest first`() {
        val settings = UnrealHelperSettings()

        settings.saveCommandLine("-game")
        settings.saveCommandLine("-server")
        settings.saveCommandLine("-game")

        assertEquals(listOf("-game", "-server"), settings.state.savedCommandLines)
    }
}

