package com.cmroche.unrealhelper.ui

import com.cmroche.unrealhelper.config.TargetPlatformConfigurationEditorModel
import com.cmroche.unrealhelper.config.TargetPlatformConfigurationsFile
import com.cmroche.unrealhelper.config.TargetPlatformEntry
import com.cmroche.unrealhelper.config.ProjectRelativePaths
import com.cmroche.unrealhelper.launch.withDerivedLaunchPaths
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.Paths
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.JToolBar
import javax.swing.table.AbstractTableModel

class TargetPlatformConfigurationDialog(
    private val project: Project,
    initialFile: TargetPlatformConfigurationsFile,
    initialSelectedName: String = "",
) : DialogWrapper(project) {
    private val settings = project.service<UnrealHelperSettings>()
    private val model = TargetPlatformConfigurationEditorModel(initialFile, initialSelectedName)
    private val configurationListModel = DefaultListModel<String>()
    private val configurationList = JBList(configurationListModel).also { list ->
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.visibleRowCount = 12
        list.preferredSize = Dimension(220, 0)
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                selectConfiguration(list.selectedValue)
            }
        }
    }
    private val entryTableModel = TargetPlatformEntryTableModel()
    private val entryTable = JBTable(entryTableModel).also { table ->
        table.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        table.fillsViewportHeight = true
        table.rowHeight = table.rowHeight.coerceAtLeast(24)
        table.columnModel.getColumn(0).preferredWidth = 110
        table.columnModel.getColumn(1).preferredWidth = 90
        table.columnModel.getColumn(2).preferredWidth = 220
        table.columnModel.getColumn(3).preferredWidth = 220
        table.columnModel.getColumn(4).preferredWidth = 180
    }
    private var updatingConfigurationList = false

    init {
        refreshConfigurationList()
        loadSelectedEntries()
        title = "Target & Platform Configurations"
        init()
    }

    fun configurations(): TargetPlatformConfigurationsFile {
        persistSelectedEntries()
        return model.snapshot()
    }

    fun selectedConfigurationName(): String = model.selectedName

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.preferredSize = Dimension(720, 420)
        panel.add(createToolbar(), BorderLayout.NORTH)
        panel.add(JBScrollPane(configurationList), BorderLayout.WEST)
        panel.add(createEntryPanel(), BorderLayout.CENTER)
        return panel
    }

    private fun createToolbar(): JToolBar =
        JToolBar().also { toolbar ->
            toolbar.isFloatable = false
            toolbar.add(JButton("Add").also { it.addActionListener { addConfiguration() } })
            toolbar.add(JButton("Duplicate").also { it.addActionListener { duplicateConfiguration() } })
            toolbar.add(JButton("Rename").also { it.addActionListener { renameConfiguration() } })
            toolbar.add(JButton("Delete").also { it.addActionListener { deleteConfiguration() } })
        }

    private fun createEntryPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.add(JBScrollPane(entryTable), BorderLayout.CENTER)
        panel.add(createEntryControls(), BorderLayout.SOUTH)
        return panel
    }

    private fun createEntryControls(): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).also { panel ->
            panel.add(JButton("Add Entry").also { it.addActionListener { addEntry() } })
            panel.add(JButton("Remove Entry").also { it.addActionListener { removeSelectedEntry() } })
            panel.add(JButton("Choose Executable").also { it.addActionListener { chooseExecutable() } })
            panel.add(JButton("Choose Working Directory").also { it.addActionListener { chooseWorkingDirectory() } })
        }

    private fun addConfiguration() {
        val name = promptForConfigurationName("Add Configuration", nextConfigurationName()) ?: return
        persistSelectedEntries()
        if (model.addConfiguration(name)) {
            refreshConfigurationList()
            loadSelectedEntries()
        } else {
            showInvalidConfigurationNameMessage()
        }
    }

    private fun duplicateConfiguration() {
        persistSelectedEntries()
        if (model.duplicateSelected()) {
            refreshConfigurationList()
            loadSelectedEntries()
        }
    }

    private fun renameConfiguration() {
        val selectedName = model.selectedName
        if (selectedName.isBlank()) return

        val name = promptForConfigurationName("Rename Configuration", selectedName) ?: return
        persistSelectedEntries()
        if (model.renameSelected(name)) {
            refreshConfigurationList()
            loadSelectedEntries()
        } else {
            showInvalidConfigurationNameMessage()
        }
    }

    private fun deleteConfiguration() {
        persistSelectedEntries()
        if (model.deleteSelected()) {
            refreshConfigurationList()
            loadSelectedEntries()
        }
    }

    private fun addEntry() {
        stopEntryCellEditing()
        entryTableModel.addRow(defaultEntry())
        val lastRow = entryTableModel.rowCount - 1
        if (lastRow >= 0) {
            entryTable.setRowSelectionInterval(lastRow, lastRow)
        }
    }

    private fun removeSelectedEntry() {
        stopEntryCellEditing()
        val selectedRow = entryTable.selectedRow
        if (selectedRow >= 0) {
            entryTableModel.removeRow(entryTable.convertRowIndexToModel(selectedRow))
        }
    }

    private fun chooseExecutable() {
        choosePath(
            title = "Choose Executable",
            chooseDirectory = false,
            columnIndex = TargetPlatformEntryTableModel.ExecutableColumn,
        )
    }

    private fun chooseWorkingDirectory() {
        choosePath(
            title = "Choose Working Directory",
            chooseDirectory = true,
            columnIndex = TargetPlatformEntryTableModel.WorkingDirectoryColumn,
        )
    }

    private fun choosePath(title: String, chooseDirectory: Boolean, columnIndex: Int) {
        stopEntryCellEditing()
        val selectedRow = entryTable.selectedRow
        if (selectedRow < 0) {
            return
        }

        val modelRow = entryTable.convertRowIndexToModel(selectedRow)
        val descriptor = FileChooserDescriptor(
            !chooseDirectory,
            chooseDirectory,
            false,
            false,
            false,
            false,
        )
        descriptor.title = title

        val initialPath = entryTableModel.valueAt(modelRow, columnIndex)
            .takeIf { it.isNotBlank() }
            ?.let { ProjectRelativePaths.resolveForUse(projectRoot(), it) }
            ?.let { LocalFileSystem.getInstance().findFileByNioFile(Paths.get(it)) }
        val selectedFile = FileChooser.chooseFile(descriptor, project, initialPath) ?: return
        val storedPath = ProjectRelativePaths.storeRelativeTo(projectRoot(), selectedFile.toNioPath().toString())
        entryTableModel.setValueAt(storedPath, modelRow, columnIndex)
    }

    private fun selectConfiguration(name: String?) {
        if (updatingConfigurationList || name.isNullOrBlank() || name == model.selectedName) {
            return
        }

        persistSelectedEntries()
        model.select(name)
        loadSelectedEntries()
    }

    private fun persistSelectedEntries() {
        stopEntryCellEditing()
        model.setEntries(entryTableModel.snapshot())
    }

    private fun stopEntryCellEditing() {
        entryTable.cellEditor?.stopCellEditing()
    }

    private fun loadSelectedEntries() {
        val selectedEntries = model.snapshot()
            .configurations
            .firstOrNull { it.name == model.selectedName }
            ?.entries
            .orEmpty()
            .map { it.withDerivedDefaults() }
        entryTableModel.setRows(selectedEntries)
    }

    private fun refreshConfigurationList() {
        updatingConfigurationList = true
        try {
            configurationListModel.clear()
            model.snapshot().configurations.forEach { configuration ->
                configurationListModel.addElement(configuration.name)
            }

            val selectedName = model.selectedName
            if (selectedName.isBlank()) {
                configurationList.clearSelection()
            } else {
                configurationList.setSelectedValue(selectedName, true)
            }
        } finally {
            updatingConfigurationList = false
        }
    }

    private fun promptForConfigurationName(title: String, initialValue: String): String? =
        JOptionPane.showInputDialog(
            configurationList,
            "Configuration name:",
            title,
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            initialValue,
        )?.toString()

    private fun showInvalidConfigurationNameMessage() {
        JOptionPane.showMessageDialog(
            configurationList,
            "Use a unique non-empty configuration name.",
            "Target & Platform Configurations",
            JOptionPane.ERROR_MESSAGE,
        )
    }

    private fun nextConfigurationName(): String {
        val existingNames = model.snapshot().configurations.map { it.name }.toSet()
        val baseName = "Configuration"
        if (baseName !in existingNames) {
            return baseName
        }

        var suffix = 2
        while ("$baseName $suffix" in existingNames) {
            suffix++
        }
        return "$baseName $suffix"
    }

    private fun defaultEntry(): TargetPlatformEntry {
        val state = settings.state
        val targetType = state.discoveredTargets.firstOrNull()?.type?.takeIf { it.isNotBlank() } ?: "Game"
        val platform = state.discoveredPlatforms.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Win64"
        return TargetPlatformEntry(targetType = targetType, platform = platform).withDerivedDefaults()
    }

    private fun TargetPlatformEntry.withDerivedDefaults(): TargetPlatformEntry =
        withDerivedLaunchPaths(settings.state, Paths.get(settings.effectivePackageDirectory()))

    private fun projectRoot() = ProjectRelativePaths.projectRoot(settings.state)
}

private class TargetPlatformEntryTableModel : AbstractTableModel() {
    private val columns = listOf("Target Type", "Platform", "Arguments", "Executable", "Working Directory")
    private var rows = mutableListOf<TargetPlatformEntry>()

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
        when (columnIndex) {
            0 -> rows[rowIndex].targetType
            1 -> rows[rowIndex].platform
            2 -> rows[rowIndex].arguments
            3 -> rows[rowIndex].executablePath
            4 -> rows[rowIndex].workingDirectory
            else -> ""
        }

    fun valueAt(rowIndex: Int, columnIndex: Int): String =
        getValueAt(rowIndex, columnIndex).toString()

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        val text = value?.toString().orEmpty()
        rows[rowIndex] = when (columnIndex) {
            0 -> rows[rowIndex].copy(targetType = text)
            1 -> rows[rowIndex].copy(platform = text)
            2 -> rows[rowIndex].copy(arguments = text)
            3 -> rows[rowIndex].copy(executablePath = text)
            4 -> rows[rowIndex].copy(workingDirectory = text)
            else -> rows[rowIndex]
        }
        fireTableCellUpdated(rowIndex, columnIndex)
    }

    fun setRows(entries: List<TargetPlatformEntry>) {
        rows = entries.toMutableList()
        fireTableDataChanged()
    }

    fun snapshot(): List<TargetPlatformEntry> = rows.toList()

    fun addRow(entry: TargetPlatformEntry) {
        rows += entry
        fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
    }

    fun removeRow(index: Int) {
        if (index !in rows.indices) return

        rows.removeAt(index)
        fireTableRowsDeleted(index, index)
    }

    companion object {
        const val ExecutableColumn = 3
        const val WorkingDirectoryColumn = 4
    }
}
