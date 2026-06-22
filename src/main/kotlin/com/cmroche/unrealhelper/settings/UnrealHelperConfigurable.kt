package com.cmroche.unrealhelper.settings

import com.cmroche.unrealhelper.args.CommandLineArguments
import com.cmroche.unrealhelper.discovery.UnrealProjectDiscoveryService
import com.cmroche.unrealhelper.discovery.UnrealTargetType
import com.cmroche.unrealhelper.launch.QuickLaunchProfileState
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.table.JBTable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.text.JTextComponent

class UnrealHelperConfigurable(private val project: Project) : SearchableConfigurable {
    private var rootPanel: JPanel? = null
    private var uprojectPathField: JBTextField? = null
    private var workspaceRootField: JBTextField? = null
    private var packageDirectoryField: JBTextField? = null
    private var engineRootField: JBTextField? = null
    private var buildConfigurationComboBox: ComboBox<String>? = null
    private var platformsField: JBTextField? = null
    private var targetTypeCheckboxes: Map<UnrealTargetType, JCheckBox> = emptyMap()
    private var discoverySummaryArea: JBTextArea? = null
    private var commandLineArea: JBTextArea? = null
    private var applyToRunDebug: JCheckBox? = null
    private var quickLaunchTable: JBTable? = null
    private var quickLaunchTableModel: QuickLaunchProfileTableModel? = null
    private val quickLaunchEditedRows = mutableMapOf<QuickLaunchProfileKey, QuickLaunchProfileRow>()

    override fun getId(): String = "com.cmroche.unrealhelper.settings"

    override fun getDisplayName(): String = "UnrealHelper"

    override fun createComponent(): JComponent {
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)

        uprojectPathField = JBTextField()
        workspaceRootField = JBTextField()
        packageDirectoryField = JBTextField()
        engineRootField = JBTextField()
        buildConfigurationComboBox = ComboBox<String>(UnrealHelperSettings.BuildConfigurations.toTypedArray())
        platformsField = JBTextField()
        discoverySummaryArea = JBTextArea(6, 72).also {
            it.isEditable = false
        }
        commandLineArea = JBTextArea(10, 72)
        applyToRunDebug = JCheckBox("Apply global arguments to Rider Run/Debug launches")
        targetTypeCheckboxes = UnrealTargetType.entries.associateWith { JCheckBox(it.name) }
        quickLaunchTableModel = QuickLaunchProfileTableModel()
        quickLaunchTable = JBTable(quickLaunchTableModel).also {
            it.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
            it.fillsViewportHeight = true
            it.rowHeight = it.rowHeight.coerceAtLeast(24)
            it.columnModel.getColumn(0).preferredWidth = 90
            it.columnModel.getColumn(1).preferredWidth = 90
            it.columnModel.getColumn(2).preferredWidth = 260
            it.columnModel.getColumn(3).preferredWidth = 220
            it.columnModel.getColumn(4).preferredWidth = 200
        }
        addQuickLaunchSelectionListeners()

        content.add(projectPanel())
        content.add(discoveryPanel())
        content.add(targetsPanel())
        content.add(quickLaunchPanel())
        content.add(globalArgsPanel())

        val panel = JPanel(BorderLayout())
        panel.add(JBScrollPane(content), BorderLayout.CENTER)
        rootPanel = panel
        reset()

        return panel
    }

    override fun isModified(): Boolean {
        val settings = project.service<UnrealHelperSettings>()
        val state = settings.state
        return uprojectPathField?.text != state.uprojectPath ||
            workspaceRootField?.text != state.workspaceRoot ||
            packageDirectoryField?.text != settings.effectivePackageDirectory() ||
            engineRootField?.text != state.engineRoot ||
            selectedBuildConfiguration() != settings.effectiveBuildConfiguration() ||
            selectedTargetTypes() != state.selectedTargetTypes ||
            parseCsv(platformsField?.text.orEmpty()) != state.selectedPlatforms ||
            commandLineArea?.text != CommandLineArguments.toEditorText(state.activeCommandLine) ||
            applyToRunDebug?.isSelected != state.applyToRunDebug ||
            quickLaunchRowsModified(state)
    }

    override fun apply() {
        stopQuickLaunchCellEditing()
        val settings = project.service<UnrealHelperSettings>()
        val state = settings.state

        state.uprojectPath = uprojectPathField?.text.orEmpty().trim()
        state.workspaceRoot = workspaceRootField?.text.orEmpty().trim()
        state.packageDirectory = packageDirectoryField?.text.orEmpty().trim()
        state.engineRoot = engineRootField?.text.orEmpty().trim()
        state.buildConfiguration = selectedBuildConfiguration()
        state.selectedTargetTypes = selectedTargetTypes().toMutableList()
        state.selectedPlatforms = parseCsv(platformsField?.text.orEmpty()).toMutableList()
        state.applyToRunDebug = applyToRunDebug?.isSelected ?: true
        settings.setActiveCommandLine(CommandLineArguments.fromEditorText(commandLineArea?.text.orEmpty()))
        quickLaunchTableRows().forEach { row ->
            val profile = state.profileFor(row.targetType, row.platform)
            profile.name = profile.name.ifBlank { "${row.targetType} ${row.platform}" }
            profile.targetType = row.targetType
            profile.platform = row.platform
            profile.executablePath = row.executablePath
            profile.workingDirectory = row.workingDirectory
            profile.arguments = row.arguments
        }
        rememberQuickLaunchTableRows()
    }

    override fun reset() {
        val settings = project.service<UnrealHelperSettings>()
        val state = settings.state
        uprojectPathField?.text = state.uprojectPath
        workspaceRootField?.text = state.workspaceRoot
        packageDirectoryField?.text = settings.effectivePackageDirectory()
        engineRootField?.text = state.engineRoot
        buildConfigurationComboBox?.selectedItem = settings.effectiveBuildConfiguration()
        platformsField?.text = state.selectedPlatforms.joinToString(", ")
        for ((targetType, checkBox) in targetTypeCheckboxes) {
            checkBox.isSelected = targetType.name in state.selectedTargetTypes
        }
        discoverySummaryArea?.text = discoverySummary(state)
        commandLineArea?.text = CommandLineArguments.toEditorText(state.activeCommandLine)
        applyToRunDebug?.isSelected = state.applyToRunDebug
        quickLaunchEditedRows.clear()
        refreshQuickLaunchRowsFromSelection(useEditedRows = false)
    }

    override fun disposeUIResources() {
        rootPanel = null
        uprojectPathField = null
        workspaceRootField = null
        packageDirectoryField = null
        engineRootField = null
        buildConfigurationComboBox = null
        platformsField = null
        targetTypeCheckboxes = emptyMap()
        discoverySummaryArea = null
        commandLineArea = null
        applyToRunDebug = null
        quickLaunchTable = null
        quickLaunchTableModel = null
        quickLaunchEditedRows.clear()
    }

    private fun projectPanel(): JPanel {
        val panel = sectionPanel("Project")
        val form = JPanel(GridBagLayout())
        addFormRow(form, 0, ".uproject:", uprojectPathField)
        addFormRow(form, 1, "Workspace Root:", workspaceRootField)
        addFormRow(form, 2, "Engine Root:", engineRootField)
        addFormRow(form, 3, "Package Dir:", packageDirectoryField)
        addFormRow(form, 4, "Build Configuration:", buildConfigurationComboBox)
        panel.add(form, BorderLayout.CENTER)
        return panel
    }

    private fun discoveryPanel(): JPanel {
        val panel = sectionPanel("Detection")
        val refreshButton = JButton("Refresh from Project Files")
        refreshButton.addActionListener {
            project.service<UnrealProjectDiscoveryService>().refresh()
            reset()
        }

        val top = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        top.add(refreshButton)
        panel.add(top, BorderLayout.NORTH)
        panel.add(JBScrollPane(discoverySummaryArea), BorderLayout.CENTER)
        return panel
    }

    private fun targetsPanel(): JPanel {
        val panel = sectionPanel("Targets And Platforms")
        val targetPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        targetTypeCheckboxes.values.forEach(targetPanel::add)

        val form = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().also {
            it.gridx = 0
            it.gridy = 0
            it.anchor = GridBagConstraints.WEST
            it.insets = Insets(0, 0, 8, 8)
        }
        form.add(JBLabel("Target Types:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        constraints.fill = GridBagConstraints.HORIZONTAL
        form.add(targetPanel, constraints)

        constraints.gridx = 0
        constraints.gridy = 1
        constraints.weightx = 0.0
        constraints.fill = GridBagConstraints.NONE
        form.add(JBLabel("Platforms:"), constraints)
        constraints.gridx = 1
        constraints.weightx = 1.0
        constraints.fill = GridBagConstraints.HORIZONTAL
        form.add(platformsField, constraints)

        panel.add(form, BorderLayout.CENTER)
        return panel
    }

    private fun globalArgsPanel(): JPanel {
        val panel = sectionPanel("Global Run/Debug Args")
        panel.add(JBLabel("Global launch arguments, one option per line:"), BorderLayout.NORTH)
        panel.add(JBScrollPane(commandLineArea), BorderLayout.CENTER)
        panel.add(applyToRunDebug, BorderLayout.SOUTH)
        return panel
    }

    private fun quickLaunchPanel(): JPanel {
        val panel = sectionPanel("Quick Launch")
        val scrollPane = JBScrollPane(quickLaunchTable).also {
            it.preferredSize = Dimension(0, 132)
        }
        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
    }

    private fun sectionPanel(title: String): JPanel =
        JPanel(BorderLayout(0, 8)).also {
            it.border = BorderFactory.createTitledBorder(title)
        }

    private fun addFormRow(form: JPanel, row: Int, label: String, field: JComponent?) {
        val labelConstraints = GridBagConstraints().also {
            it.gridx = 0
            it.gridy = row
            it.anchor = GridBagConstraints.WEST
            it.insets = Insets(0, 0, 8, 8)
        }
        form.add(JBLabel(label), labelConstraints)

        val fieldConstraints = GridBagConstraints().also {
            it.gridx = 1
            it.gridy = row
            it.weightx = 1.0
            it.fill = GridBagConstraints.HORIZONTAL
            it.insets = Insets(0, 0, 8, 0)
        }
        form.add(field, fieldConstraints)
    }

    private fun selectedTargetTypes(): List<String> =
        targetTypeCheckboxes
            .filterValues { it.isSelected }
            .keys
            .map { it.name }

    private fun selectedBuildConfiguration(): String =
        (buildConfigurationComboBox?.selectedItem as? String)
            ?.takeIf { it in UnrealHelperSettings.BuildConfigurations }
            ?: UnrealHelperSettings.DefaultBuildConfiguration

    private fun addQuickLaunchSelectionListeners() {
        targetTypeCheckboxes.values.forEach { checkBox ->
            checkBox.addActionListener {
                refreshQuickLaunchRowsFromSelection()
            }
        }
        platformsField?.document?.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = refreshQuickLaunchRowsFromSelection()

            override fun removeUpdate(event: DocumentEvent) = refreshQuickLaunchRowsFromSelection()

            override fun changedUpdate(event: DocumentEvent) = refreshQuickLaunchRowsFromSelection()
        })
    }

    private fun refreshQuickLaunchRowsFromSelection(useEditedRows: Boolean = true) {
        if (useEditedRows) {
            rememberQuickLaunchTableRows()
        }

        val state = project.service<UnrealHelperSettings>().state
        val rows = selectedTargetTypes().flatMap { targetType ->
            parseCsv(platformsField?.text.orEmpty()).map { platform ->
                quickLaunchEditedRows[QuickLaunchProfileKey(targetType, platform)]
                    ?: state.quickLaunchProfile(targetType, platform)?.toRow()
                    ?: QuickLaunchProfileRow(targetType = targetType, platform = platform)
            }
        }
        quickLaunchTableModel?.setRows(rows)
    }

    private fun quickLaunchRowsModified(state: UnrealHelperSettingsState): Boolean =
        quickLaunchTableRows().any { row ->
            val savedRow = state.quickLaunchProfile(row.targetType, row.platform)?.toRow()
                ?: QuickLaunchProfileRow(targetType = row.targetType, platform = row.platform)
            row != savedRow
        }

    private fun quickLaunchTableRows(includeActiveEditor: Boolean = true): List<QuickLaunchProfileRow> {
        val rows = quickLaunchTableModel?.snapshot().orEmpty()
        return if (includeActiveEditor) {
            quickLaunchRowsWithActiveEditor(rows, quickLaunchTable)
        } else {
            rows
        }
    }

    private fun rememberQuickLaunchTableRows() {
        rememberQuickLaunchRows(quickLaunchTableRows(), quickLaunchEditedRows)
    }

    private fun stopQuickLaunchCellEditing() {
        quickLaunchTable?.cellEditor?.stopCellEditing()
    }

    private fun discoverySummary(state: UnrealHelperSettingsState): String {
        val lines = mutableListOf<String>()
        if (state.discoveredTargets.isEmpty()) {
            lines += "Targets: none detected"
        } else {
            lines += "Targets:"
            lines += state.discoveredTargets.map { "- ${it.name} (${it.type}) ${it.path}" }
        }

        lines += "Platforms: ${state.discoveredPlatforms.joinToString(", ").ifBlank { "none detected" }}"

        if (state.discoveryWarnings.isNotEmpty()) {
            lines += ""
            lines += "Warnings:"
            lines += state.discoveryWarnings.map { "- $it" }
        }

        return lines.joinToString("\n")
    }

    private fun parseCsv(value: String): List<String> =
        value.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}

internal data class QuickLaunchProfileKey(
    val targetType: String,
    val platform: String,
)

internal data class QuickLaunchProfileRow(
    val targetType: String,
    val platform: String,
    val executablePath: String = "",
    val workingDirectory: String = "",
    val arguments: String = "",
)

internal class QuickLaunchProfileTableModel : AbstractTableModel() {
    private val columns = listOf("Target Type", "Platform", "Executable", "Working Directory", "Arguments")
    private var rows = mutableListOf<QuickLaunchProfileRow>()

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex >= EditableColumnStart

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
        rows[rowIndex].valueAt(columnIndex)

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        if (!isCellEditable(rowIndex, columnIndex)) return

        val text = value?.toString().orEmpty()
        rows[rowIndex] = when (columnIndex) {
            2 -> rows[rowIndex].copy(executablePath = text)
            3 -> rows[rowIndex].copy(workingDirectory = text)
            4 -> rows[rowIndex].copy(arguments = text)
            else -> rows[rowIndex]
        }
        fireTableCellUpdated(rowIndex, columnIndex)
    }

    fun setRows(rows: List<QuickLaunchProfileRow>) {
        this.rows = rows.toMutableList()
        fireTableDataChanged()
    }

    fun snapshot(): List<QuickLaunchProfileRow> = rows.toList()

    private fun QuickLaunchProfileRow.valueAt(columnIndex: Int): String =
        when (columnIndex) {
            0 -> targetType
            1 -> platform
            2 -> executablePath
            3 -> workingDirectory
            4 -> arguments
            else -> ""
        }

    private companion object {
        private const val EditableColumnStart = 2
    }
}

internal fun quickLaunchRowsWithActiveEditor(
    rows: List<QuickLaunchProfileRow>,
    table: JTable?,
): List<QuickLaunchProfileRow> {
    if (table?.isEditing != true) return rows

    val viewRow = table.editingRow
    val viewColumn = table.editingColumn
    if (viewRow < 0 || viewColumn < 0) return rows

    val modelRow = table.convertRowIndexToModel(viewRow)
    val modelColumn = table.convertColumnIndexToModel(viewColumn)
    val editorValue = (table.editorComponent as? JTextComponent)?.text
        ?: table.cellEditor?.cellEditorValue?.toString().orEmpty()
    return rows.withQuickLaunchEditorValue(modelRow, modelColumn, editorValue)
}

internal fun rememberQuickLaunchRows(
    rows: List<QuickLaunchProfileRow>,
    editedRows: MutableMap<QuickLaunchProfileKey, QuickLaunchProfileRow>,
) {
    rows.forEach { row ->
        editedRows[QuickLaunchProfileKey(row.targetType, row.platform)] = row
    }
}

private fun List<QuickLaunchProfileRow>.withQuickLaunchEditorValue(
    rowIndex: Int,
    columnIndex: Int,
    value: String,
): List<QuickLaunchProfileRow> {
    if (rowIndex !in indices) return this

    val updatedRow = when (columnIndex) {
        2 -> this[rowIndex].copy(executablePath = value)
        3 -> this[rowIndex].copy(workingDirectory = value)
        4 -> this[rowIndex].copy(arguments = value)
        else -> this[rowIndex]
    }
    if (updatedRow == this[rowIndex]) return this

    return toMutableList().also {
        it[rowIndex] = updatedRow
    }
}

private fun UnrealHelperSettingsState.quickLaunchProfile(
    targetType: String,
    platform: String,
): QuickLaunchProfileState? =
    quickLaunchProfiles.firstOrNull { it.targetType == targetType && it.platform == platform }

private fun QuickLaunchProfileState.toRow(): QuickLaunchProfileRow =
    QuickLaunchProfileRow(
        targetType = targetType,
        platform = platform,
        executablePath = executablePath,
        workingDirectory = workingDirectory,
        arguments = arguments,
    )
