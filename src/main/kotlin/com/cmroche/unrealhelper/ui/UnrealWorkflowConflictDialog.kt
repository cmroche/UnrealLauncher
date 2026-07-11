package com.cmroche.unrealhelper.ui

import com.cmroche.unrealhelper.execution.UnrealWorkflowConflict
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

internal object UnrealWorkflowConflictDialog {
    fun confirm(project: Project, conflict: UnrealWorkflowConflict): Boolean =
        Messages.showDialog(
            project,
            conflictMessage(conflict),
            "Unreal Workflow Already Running",
            arrayOf("Stop and Restart", "Keep Running"),
            1,
            Messages.getWarningIcon(),
        ) == 0
}

internal fun conflictMessage(conflict: UnrealWorkflowConflict): String = buildList {
    add("Another Unreal workflow or conflicting launch is active.")
    add("")
    addSection("Running", conflict.runningActions)
    addSection("Queued", conflict.queuedActions)
    addSection("Launched Processes", conflict.launchedProcesses.map { it.title })
    add("Stop the listed work and start the new request?")
}.joinToString("\n")

private fun MutableList<String>.addSection(title: String, values: List<String>) {
    if (values.isEmpty()) return
    add("$title:")
    values.forEach { add("- $it") }
    add("")
}
