package com.cmroche.unrealhelper.ui

import com.cmroche.unrealhelper.config.TargetPlatformEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JCheckBox
import javax.swing.JTable

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
        assertEquals(Boolean::class.javaObjectType, model.getColumnClass(TargetPlatformEntryTableModel.CookColumn))
        assertEquals(Boolean::class.javaObjectType, model.getColumnClass(TargetPlatformEntryTableModel.IncrementalCookColumn))
    }

    @Test
    fun `cook column uses a checkbox renderer and editor`() {
        val model = TargetPlatformEntryTableModel().also {
            it.setRows(listOf(TargetPlatformEntry(targetName = "Lyra", platform = "Mac")))
        }
        val table = JTable(model)

        val renderer = table.getCellRenderer(0, TargetPlatformEntryTableModel.CookColumn)
        val rendered = renderer.getTableCellRendererComponent(
            table, false, false, false, 0, TargetPlatformEntryTableModel.CookColumn,
        )
        val editor = table.getCellEditor(0, TargetPlatformEntryTableModel.CookColumn)
        val edited = editor.getTableCellEditorComponent(
            table, false, true, 0, TargetPlatformEntryTableModel.CookColumn,
        )

        assertTrue(rendered is JCheckBox)
        assertTrue(edited is JCheckBox)
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

        assertFalse(model.snapshot().single().cookOnLaunch)
        assertFalse(model.snapshot().single().incrementalCookOnLaunch)
        assertFalse(model.isCellEditable(0, TargetPlatformEntryTableModel.IncrementalCookColumn))
        assertEquals("MissingSavedTarget", model.snapshot().single().targetName)
    }

    @Test
    fun `incremental cook renderer is visually disabled when cook is off`() {
        val model = TargetPlatformEntryTableModel().also {
            it.setRows(listOf(TargetPlatformEntry(targetName = "Lyra", platform = "Mac")))
        }
        val table = JTable(model)

        val component = IncrementalCookCellRenderer().getTableCellRendererComponent(
            table, false, false, false, 0, TargetPlatformEntryTableModel.IncrementalCookColumn,
        )

        assertFalse(component.isEnabled)
    }

    @Test
    fun `entry edit action uses the selected editable cell`() {
        val model = TargetPlatformEntryTableModel().also {
            it.setRows(listOf(TargetPlatformEntry(targetName = "Lyra", platform = "Mac")))
        }
        val table = JTable(model)
        table.changeSelection(0, TargetPlatformEntryTableModel.ArgumentsColumn, false, false)

        assertEquals(0 to TargetPlatformEntryTableModel.ArgumentsColumn, editableEntryCell(table))
    }

    @Test
    fun `entry edit action falls back when the selected cell is disabled`() {
        val model = TargetPlatformEntryTableModel().also {
            it.setRows(listOf(TargetPlatformEntry(targetName = "Lyra", platform = "Mac")))
        }
        val table = JTable(model)
        table.changeSelection(0, TargetPlatformEntryTableModel.IncrementalCookColumn, false, false)

        assertEquals(0 to TargetPlatformEntryTableModel.TargetColumn, editableEntryCell(table))
    }
}
