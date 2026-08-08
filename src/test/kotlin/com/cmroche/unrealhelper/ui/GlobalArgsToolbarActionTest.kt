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

    @Test
    fun `selector restores persisted active command line as its initial choice`() {
        val settings = UnrealHelperSettings().also {
            it.rememberCommandLine("-game -log")
            it.rememberCommandLine("-server -log")
            it.setActiveCommandLine("-game -log")
        }
        val comboBox = GlobalArgsComboBox().also { it.isEditable = true }

        comboBox.restoreInitialSelection(settings)

        assertEquals("-game -log", comboBox.selectedItem)
        assertEquals("-game -log", comboBox.editor.item)
        assertEquals(listOf("-game -log", "-server -log"), comboBox.model.items())
    }

    @Test
    fun `repeated action updates do not replace the restored selector value`() {
        val settings = UnrealHelperSettings().also {
            it.setActiveCommandLine("-noop")
        }
        val comboBox = GlobalArgsComboBox().also { it.isEditable = true }
        comboBox.restoreInitialSelection(settings)
        comboBox.editor.item = "-game"

        comboBox.restoreInitialSelection(settings)

        assertEquals("-game", comboBox.editor.item)
    }

    private fun <T> javax.swing.ComboBoxModel<T>.items(): List<T> =
        (0 until size).map(::getElementAt)
}
