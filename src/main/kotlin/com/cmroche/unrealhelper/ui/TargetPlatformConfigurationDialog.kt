package com.cmroche.unrealhelper.ui

import com.cmroche.unrealhelper.config.TargetPlatformConfigurationEditorModel
import com.cmroche.unrealhelper.config.TargetPlatformConfigurationsFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToolBar

class TargetPlatformConfigurationDialog(
    project: Project,
    initialFile: TargetPlatformConfigurationsFile,
) : DialogWrapper(project) {
    private val model = TargetPlatformConfigurationEditorModel(initialFile)

    init {
        title = "Target & Platform Configurations"
        init()
    }

    fun configurations(): TargetPlatformConfigurationsFile = model.snapshot()

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.preferredSize = Dimension(720, 420)
        panel.add(createToolbar(), BorderLayout.NORTH)
        panel.add(JBScrollPane(createConfigurationList()), BorderLayout.WEST)
        panel.add(JPanel(BorderLayout()), BorderLayout.CENTER)
        return panel
    }

    private fun createToolbar(): JToolBar =
        JToolBar().also { toolbar ->
            toolbar.isFloatable = false
            toolbar.add(JButton("Add"))
            toolbar.add(JButton("Duplicate"))
            toolbar.add(JButton("Rename"))
            toolbar.add(JButton("Delete"))
        }

    private fun createConfigurationList(): JBList<String> {
        val listModel = DefaultListModel<String>()
        model.snapshot().configurations.forEach { listModel.addElement(it.name) }

        return JBList(listModel).also { list ->
            list.visibleRowCount = 12
            list.setSelectedValue(model.selectedName, true)
            list.preferredSize = Dimension(220, 0)
        }
    }
}
