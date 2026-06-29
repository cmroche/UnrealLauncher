package com.cmroche.unrealhelper.actions

import com.cmroche.unrealhelper.config.SelectedTargetPlatformConfigurationResult
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

internal object UnrealActionMessages {
    fun showError(project: Project, message: String) {
        Messages.showErrorDialog(project, message, "UnrealHelper")
    }

    fun selectedConfigurationError(result: SelectedTargetPlatformConfigurationResult): String? =
        when (result) {
            is SelectedTargetPlatformConfigurationResult.Valid -> null
            is SelectedTargetPlatformConfigurationResult.MissingFile ->
                "Create a Target & Platform configuration before running this action."
            is SelectedTargetPlatformConfigurationResult.MalformedFile ->
                "Could not read ${result.path}: ${result.message}"
            is SelectedTargetPlatformConfigurationResult.DuplicateNames ->
                "Target & Platform configuration names must be unique: ${result.names.joinToString(", ")}"
            is SelectedTargetPlatformConfigurationResult.BlankNames ->
                "Target & Platform configuration names must be non-empty. Blank names found: ${result.count}."
            SelectedTargetPlatformConfigurationResult.NoSelection ->
                "Select a Target & Platform configuration before running this action."
            is SelectedTargetPlatformConfigurationResult.StaleSelection ->
                "The selected Target & Platform configuration no longer exists. Select a configuration before running this action."
            is SelectedTargetPlatformConfigurationResult.EmptyConfiguration ->
                "Target & Platform configuration '${result.name}' has no entries."
            is SelectedTargetPlatformConfigurationResult.InvalidEntries ->
                "Target & Platform configuration '${result.name}' cannot run:\n${result.messages.joinToString("\n")}"
        }
}
