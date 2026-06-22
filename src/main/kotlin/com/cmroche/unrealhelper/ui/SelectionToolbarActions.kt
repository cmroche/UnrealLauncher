package com.cmroche.unrealhelper.ui

import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import javax.swing.JComponent

class TargetTypesToolbarAction : SelectionToolbarAction(
    emptyText = "Targets",
    labelPrefix = "Targets",
    fallbackValues = listOf("Game", "Client", "Server"),
) {
    override fun discoveredValues(state: UnrealHelperSettingsState): List<String> =
        state.discoveredTargets.map { it.type }

    override fun selectedValues(state: UnrealHelperSettingsState): List<String> =
        state.selectedTargetTypes

    override fun setSelectedValues(state: UnrealHelperSettingsState, values: List<String>) {
        state.selectedTargetTypes = values.toMutableList()
    }
}

class PlatformsToolbarAction : SelectionToolbarAction(
    emptyText = "Platforms",
    labelPrefix = "Platforms",
    fallbackValues = listOf("Win64", "Mac", "Linux"),
) {
    override fun discoveredValues(state: UnrealHelperSettingsState): List<String> =
        state.discoveredPlatforms

    override fun selectedValues(state: UnrealHelperSettingsState): List<String> =
        state.selectedPlatforms

    override fun setSelectedValues(state: UnrealHelperSettingsState, values: List<String>) {
        state.selectedPlatforms = values.toMutableList()
    }
}

abstract class SelectionToolbarAction(
    private val emptyText: String,
    private val labelPrefix: String,
    private val fallbackValues: List<String>,
) : ComboBoxAction(), DumbAware {
    init {
        templatePresentation.text = emptyText
    }

    final override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isVisible = project != null

        if (project == null) {
            event.presentation.isEnabled = false
            event.presentation.text = emptyText
            return
        }

        val state = project.service<UnrealHelperSettings>().state
        val selected = normalized(selectedValues(state))
        event.presentation.isEnabled = state.uprojectPath.isNotBlank()
        event.presentation.text = if (selected.isEmpty()) {
            emptyText
        } else {
            "$labelPrefix: ${selected.joinToString(", ")}"
        }
    }

    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    final override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return DefaultActionGroup()
        val state = project.service<UnrealHelperSettings>().state

        return DefaultActionGroup().also { group ->
            availableValues(state).forEach { value ->
                group.add(SelectionToggleAction(project, value))
            }
        }
    }

    protected abstract fun discoveredValues(state: UnrealHelperSettingsState): List<String>

    protected abstract fun selectedValues(state: UnrealHelperSettingsState): List<String>

    protected abstract fun setSelectedValues(state: UnrealHelperSettingsState, values: List<String>)

    private fun availableValues(state: UnrealHelperSettingsState): List<String> =
        normalized(discoveredValues(state) + selectedValues(state) + fallbackValues)

    private fun normalized(values: List<String>): List<String> =
        values
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private inner class SelectionToggleAction(
        private val project: Project,
        private val value: String,
    ) : ToggleAction(value), DumbAware {
        override fun isSelected(event: AnActionEvent): Boolean {
            val state = project.service<UnrealHelperSettings>().state
            return value in normalized(selectedValues(state))
        }

        override fun setSelected(event: AnActionEvent, selected: Boolean) {
            val state = project.service<UnrealHelperSettings>().state
            val currentValues = normalized(selectedValues(state))
            val updatedValues = if (selected) {
                currentValues + value
            } else {
                currentValues.filterNot { it == value }
            }

            setSelectedValues(state, normalized(updatedValues))
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }
}
