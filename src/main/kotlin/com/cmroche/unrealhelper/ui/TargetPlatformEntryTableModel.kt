package com.cmroche.unrealhelper.ui

import com.cmroche.unrealhelper.config.TargetPlatformEntry
import javax.swing.table.AbstractTableModel

class TargetPlatformEntryTableModel : AbstractTableModel() {
    private val columns = listOf("Build Target", "Platform", "Arguments", "Cook", "Incremental Cook")
    private var mutableRows = mutableListOf<TargetPlatformEntry>()

    val rows: List<TargetPlatformEntry>
        get() = mutableRows

    override fun getRowCount(): Int = mutableRows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getColumnClass(column: Int): Class<*> =
        if (column == CookColumn || column == IncrementalCookColumn) {
            Boolean::class.javaObjectType
        } else {
            String::class.java
        }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
        columnIndex != IncrementalCookColumn || mutableRows[rowIndex].cookOnLaunch

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
        when (columnIndex) {
            TargetColumn -> mutableRows[rowIndex].targetName
            PlatformColumn -> mutableRows[rowIndex].platform
            ArgumentsColumn -> mutableRows[rowIndex].arguments
            CookColumn -> mutableRows[rowIndex].cookOnLaunch
            IncrementalCookColumn -> mutableRows[rowIndex].incrementalCookOnLaunch
            else -> ""
        }

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        val row = mutableRows[rowIndex]
        mutableRows[rowIndex] = when (columnIndex) {
            TargetColumn -> row.copy(targetName = value?.toString().orEmpty())
            PlatformColumn -> row.copy(platform = value?.toString().orEmpty())
            ArgumentsColumn -> row.copy(arguments = value?.toString().orEmpty())
            CookColumn -> {
                val cook = value as? Boolean ?: false
                row.copy(
                    cookOnLaunch = cook,
                    incrementalCookOnLaunch = row.incrementalCookOnLaunch && cook,
                )
            }
            IncrementalCookColumn -> row.copy(
                incrementalCookOnLaunch = row.cookOnLaunch && (value as? Boolean ?: false),
            )
            else -> row
        }
        fireTableCellUpdated(rowIndex, columnIndex)
        if (columnIndex == CookColumn) {
            fireTableCellUpdated(rowIndex, IncrementalCookColumn)
        }
    }

    fun setRows(entries: List<TargetPlatformEntry>) {
        mutableRows = entries.toMutableList()
        fireTableDataChanged()
    }

    fun snapshot(): List<TargetPlatformEntry> = mutableRows.toList()

    fun addRow(entry: TargetPlatformEntry) {
        mutableRows += entry
        fireTableRowsInserted(mutableRows.lastIndex, mutableRows.lastIndex)
    }

    fun removeRow(index: Int) {
        if (index !in mutableRows.indices) return

        mutableRows.removeAt(index)
        fireTableRowsDeleted(index, index)
    }

    companion object {
        const val TargetColumn = 0
        const val PlatformColumn = 1
        const val ArgumentsColumn = 2
        const val CookColumn = 3
        const val IncrementalCookColumn = 4
    }
}
