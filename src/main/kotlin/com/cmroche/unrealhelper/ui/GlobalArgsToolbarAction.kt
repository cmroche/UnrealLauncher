package com.cmroche.unrealhelper.ui

import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

class GlobalArgsToolbarAction : DumbAwareAction("UnrealHelper Args"), CustomComponentAction {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        editArgs(project, null)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val comboBox = ComboBox<String>()
        val editButton = JButton("Edit")
        val panel = JPanel(BorderLayout(4, 0))
        var refreshing = false

        comboBox.isEditable = true
        comboBox.prototypeDisplayValue = "-game -windowed -resx=1080 -resy=1920 -log -newconsole"
        comboBox.preferredSize = Dimension(440, comboBox.preferredSize.height)
        comboBox.toolTipText = "UnrealHelper global launch arguments"

        fun project(): Project? = projectFor(panel)

        fun refresh() {
            val currentProject = project() ?: return
            val settings = currentProject.service<UnrealHelperSettings>()
            val state = settings.state
            val values = listOf(state.activeCommandLine)
                .filter { it.isNotBlank() } + settings.knownCommandLines()

            refreshing = true
            comboBox.model = DefaultComboBoxModel(values.distinct().toTypedArray())
            comboBox.selectedItem = state.activeCommandLine
            comboBox.editor.item = state.activeCommandLine
            refreshing = false
        }

        comboBox.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = refresh()
            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) = Unit
            override fun popupMenuCanceled(event: PopupMenuEvent) = Unit
        })

        comboBox.addActionListener {
            if (refreshing) return@addActionListener

            val currentProject = project() ?: return@addActionListener
            val commandLine = comboBox.editor.item?.toString().orEmpty()
            currentProject.service<UnrealHelperSettings>().setActiveCommandLine(commandLine)
        }

        editButton.toolTipText = "Open the expanded global launch arguments editor"
        editButton.addActionListener {
            val currentProject = project() ?: return@addActionListener
            editArgs(currentProject, comboBox)
        }

        panel.add(comboBox, BorderLayout.CENTER)
        panel.add(editButton, BorderLayout.EAST)
        return panel
    }

    private fun editArgs(project: Project, comboBox: ComboBox<String>?) {
        val settings = project.service<UnrealHelperSettings>()
        val dialog = GlobalArgsEditorDialog(project, settings.state.activeCommandLine)

        if (dialog.showAndGet()) {
            settings.setActiveCommandLine(dialog.commandLine())
            comboBox?.editor?.item = settings.state.activeCommandLine
        }
    }

    private fun projectFor(component: JComponent): Project? {
        val dataContext = DataManager.getInstance().getDataContext(component)
        return CommonDataKeys.PROJECT.getData(dataContext)
    }
}
