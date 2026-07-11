package com.cmroche.unrealhelper.ui

import com.cmroche.unrealhelper.config.TargetPlatformConfigurationEditorModel
import com.cmroche.unrealhelper.config.TargetPlatformConfigurationsFile
import com.cmroche.unrealhelper.config.TargetPlatformEntry
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.DefaultCellEditor
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.JToolBar
import javax.swing.table.DefaultTableCellRenderer

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
        table.columnModel.getColumn(TargetPlatformEntryTableModel.TargetColumn).preferredWidth = 180
        table.columnModel.getColumn(TargetPlatformEntryTableModel.PlatformColumn).preferredWidth = 100
        table.columnModel.getColumn(TargetPlatformEntryTableModel.ArgumentsColumn).preferredWidth = 220
        table.columnModel.getColumn(TargetPlatformEntryTableModel.CookColumn).preferredWidth = 70
        table.columnModel.getColumn(TargetPlatformEntryTableModel.IncrementalCookColumn).preferredWidth = 110
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
        entryTableModel.setRows(selectedEntries)
        configureEntryEditors()
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
        val targetName = state.discoveredTargets.firstOrNull()?.name?.trim().orEmpty()
        val platform = state.discoveredPlatforms.firstOrNull()?.trim().orEmpty()
        return TargetPlatformEntry(targetName = targetName, platform = platform)
    }

    private fun configureEntryEditors() {
        val state = settings.state
        val targetNames = (state.discoveredTargets.map { it.name } + entryTableModel.snapshot().map { it.targetName })
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val platforms = (state.discoveredPlatforms + entryTableModel.snapshot().map { it.platform })
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val targetTypes = state.discoveredTargets
            .associate { it.name.trim() to it.type.trim() }

        val targetComboBox = JComboBox(targetNames.toTypedArray()).also { comboBox ->
            comboBox.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component = super.getListCellRendererComponent(
                    list,
                    targetLabel(value?.toString().orEmpty(), targetTypes),
                    index,
                    isSelected,
                    cellHasFocus,
                )
            }
        }
        entryTable.columnModel.getColumn(TargetPlatformEntryTableModel.TargetColumn).apply {
            cellEditor = DefaultCellEditor(targetComboBox)
            cellRenderer = object : DefaultTableCellRenderer() {
                override fun setValue(value: Any?) {
                    super.setValue(targetLabel(value?.toString().orEmpty(), targetTypes))
                }
            }
        }
        entryTable.columnModel.getColumn(TargetPlatformEntryTableModel.PlatformColumn).cellEditor =
            DefaultCellEditor(JComboBox(platforms.toTypedArray()))
    }

    private fun targetLabel(targetName: String, targetTypes: Map<String, String>): String {
        val targetType = targetTypes[targetName].orEmpty()
        return if (targetType.isBlank()) targetName else "$targetName ($targetType)"
    }
}
