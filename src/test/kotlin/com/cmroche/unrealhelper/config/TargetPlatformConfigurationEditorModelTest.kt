package com.cmroche.unrealhelper.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetPlatformConfigurationEditorModelTest {
    @Test
    fun `add creates uniquely named empty configuration`() {
        val model = TargetPlatformConfigurationEditorModel(
            TargetPlatformConfigurationsFile(
                configurations = listOf(TargetPlatformConfiguration("Game")),
            ),
        )

        assertFalse(model.addConfiguration("Game"))
        assertTrue(model.addConfiguration("Server"))

        assertEquals("Server", model.selectedName)
        assertEquals(
            TargetPlatformConfigurationsFile(
                configurations = listOf(
                    TargetPlatformConfiguration("Game"),
                    TargetPlatformConfiguration("Server"),
                ),
            ),
            model.snapshot(),
        )
    }

    @Test
    fun `duplicate uses copy suffix and preserves entries`() {
        val entries = listOf(
            TargetPlatformEntry(targetType = "Game", platform = "Win64", arguments = "-log"),
            TargetPlatformEntry(targetType = "Server", platform = "Linux", arguments = "/Game/Maps/Arena"),
        )
        val model = TargetPlatformConfigurationEditorModel(
            TargetPlatformConfigurationsFile(
                configurations = listOf(TargetPlatformConfiguration("Game", entries)),
            ),
        )

        assertTrue(model.duplicateSelected())

        assertEquals("Game Copy", model.selectedName)
        assertEquals(
            TargetPlatformConfigurationsFile(
                configurations = listOf(
                    TargetPlatformConfiguration("Game", entries),
                    TargetPlatformConfiguration("Game Copy", entries),
                ),
            ),
            model.snapshot(),
        )
    }

    @Test
    fun `rename selected configuration requires unique nonblank name`() {
        val model = TargetPlatformConfigurationEditorModel(
            TargetPlatformConfigurationsFile(
                configurations = listOf(
                    TargetPlatformConfiguration("Game"),
                    TargetPlatformConfiguration("Editor"),
                ),
            ),
        )

        assertFalse(model.renameSelected(""))
        assertFalse(model.renameSelected("  "))
        assertFalse(model.renameSelected("Editor"))
        assertTrue(model.renameSelected("Shipping"))

        assertEquals("Shipping", model.selectedName)
        assertEquals(
            TargetPlatformConfigurationsFile(
                configurations = listOf(
                    TargetPlatformConfiguration("Shipping"),
                    TargetPlatformConfiguration("Editor"),
                ),
            ),
            model.snapshot(),
        )
    }

    @Test
    fun `delete selected clears selection`() {
        val model = TargetPlatformConfigurationEditorModel(
            TargetPlatformConfigurationsFile(
                configurations = listOf(TargetPlatformConfiguration("Game")),
            ),
        )

        assertTrue(model.deleteSelected())

        assertEquals("", model.selectedName)
        assertEquals(TargetPlatformConfigurationsFile(), model.snapshot())
    }

    @Test
    fun `entry operations preserve duplicates`() {
        val model = TargetPlatformConfigurationEditorModel(
            TargetPlatformConfigurationsFile(
                configurations = listOf(TargetPlatformConfiguration("Game")),
            ),
        )
        val first = TargetPlatformEntry(targetType = "Game", platform = "Win64", arguments = "-log")
        val second = TargetPlatformEntry(targetType = "Game", platform = "Win64", arguments = "-trace=cpu")

        assertTrue(model.addEntry(first))
        assertTrue(model.addEntry(second))
        assertEquals(listOf(first, second), model.snapshot().configurations.single().entries)

        val replacement = listOf(
            TargetPlatformEntry(targetType = " Game ", platform = " Win64 ", arguments = " -stdout "),
            TargetPlatformEntry(targetType = " Game ", platform = " Win64 ", arguments = " -FullStdOutLogOutput "),
        )
        assertTrue(model.setEntries(replacement))

        assertEquals(
            listOf(
                TargetPlatformEntry(targetType = "Game", platform = "Win64", arguments = "-stdout"),
                TargetPlatformEntry(targetType = "Game", platform = "Win64", arguments = "-FullStdOutLogOutput"),
            ),
            model.snapshot().configurations.single().entries,
        )
    }
}
