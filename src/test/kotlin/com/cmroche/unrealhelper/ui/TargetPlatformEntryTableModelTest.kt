package com.cmroche.unrealhelper.ui

import com.cmroche.unrealhelper.config.TargetPlatformEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetPlatformEntryTableModelTest {
    @Test
    fun `model exposes target platform arguments and cook columns`() {
        val model = TargetPlatformEntryTableModel()

        assertEquals(5, model.columnCount)
        assertEquals(
            listOf("Build Target", "Platform", "Arguments", "Cook", "Incremental Cook"),
            (0 until model.columnCount).map(model::getColumnName),
        )
        assertEquals(String::class.java, model.getColumnClass(TargetPlatformEntryTableModel.TargetColumn))
        assertEquals(Boolean::class.java, model.getColumnClass(TargetPlatformEntryTableModel.CookColumn))
        assertEquals(Boolean::class.java, model.getColumnClass(TargetPlatformEntryTableModel.IncrementalCookColumn))
    }

    @Test
    fun `disabling cook clears incremental cook and disables its editor`() {
        val model = TargetPlatformEntryTableModel()
        model.setRows(
            listOf(
                TargetPlatformEntry(
                    targetName = "MissingSavedTarget",
                    platform = "Linux",
                    arguments = "-log",
                    cookOnLaunch = true,
                    incrementalCookOnLaunch = true,
                ),
            ),
        )

        assertTrue(model.isCellEditable(0, TargetPlatformEntryTableModel.IncrementalCookColumn))

        model.setValueAt(false, 0, TargetPlatformEntryTableModel.CookColumn)

        assertFalse(model.rows.single().cookOnLaunch)
        assertFalse(model.rows.single().incrementalCookOnLaunch)
        assertFalse(model.isCellEditable(0, TargetPlatformEntryTableModel.IncrementalCookColumn))
        assertEquals("MissingSavedTarget", model.snapshot().single().targetName)
    }
}
