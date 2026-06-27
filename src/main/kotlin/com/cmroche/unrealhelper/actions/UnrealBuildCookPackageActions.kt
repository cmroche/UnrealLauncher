package com.cmroche.unrealhelper.actions

import com.cmroche.unrealhelper.command.UnrealCommand
import com.cmroche.unrealhelper.command.UnrealCommandBuilder
import com.cmroche.unrealhelper.command.UnrealCommandContext
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.cmroche.unrealhelper.settings.UnrealTargetState
import com.cmroche.unrealhelper.terminal.UnrealTerminalRunner
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import java.nio.file.Path

internal class UnrealBuildAction : UnrealBuildCookPackageAction(
    commandFactory = { UnrealCommandBuilder.build(it) },
    deduplicateCommands = false,
)

internal class UnrealCookAction : UnrealBuildCookPackageAction(
    commandFactory = { UnrealCommandBuilder.cook(it) },
    deduplicateCommands = true,
)

internal class UnrealPackageAction : UnrealBuildCookPackageAction(
    commandFactory = { UnrealCommandBuilder.packageProject(it) },
    deduplicateCommands = true,
)

internal abstract class UnrealBuildCookPackageAction(
    private val commandFactory: (UnrealCommandContext) -> UnrealCommand,
    private val deduplicateCommands: Boolean,
) : AnAction(), DumbAware {
    final override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isVisible = project != null

        if (project == null) {
            event.presentation.isEnabled = false
            return
        }

        val state = project.service<UnrealHelperSettings>().state
        event.presentation.isEnabled = buildCookPackageActionEnabled(state)
    }

    final override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val settings = project.service<UnrealHelperSettings>()
        val state = settings.state

        val error = buildCookPackageValidationError(state, project.basePath)
        if (error != null) {
            notifyError(project, error)
            return
        }

        val runner = UnrealTerminalRunner(project)
        createUnrealCommands(settings, commandFactory, deduplicateCommands, project.basePath)
            .forEach(runner::run)
    }

    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun notifyError(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("UnrealHelper")
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }
}

internal data class UnrealResolvedCommandTarget(
    val name: String,
    val type: String,
)

internal fun createUnrealCommands(
    settings: UnrealHelperSettings,
    commandFactory: (UnrealCommandContext) -> UnrealCommand,
    deduplicate: Boolean,
    projectBasePath: String? = null,
): List<UnrealCommand> {
    val commands = createUnrealCommandContexts(settings, projectBasePath).map(commandFactory)
    return if (deduplicate) uniqueCommands(commands) else commands
}

internal fun uniqueCommands(commands: List<UnrealCommand>): List<UnrealCommand> =
    commands.distinctBy { UnrealCommandIdentity(it.asList(), it.workingDirectory) }

internal fun createUnrealCommandContexts(
    settings: UnrealHelperSettings,
    projectBasePath: String? = null,
): List<UnrealCommandContext> {
    val state = settings.state
    val uprojectPath = Path.of(state.uprojectPath)
    val workspaceRoot = workspaceRootPath(state, uprojectPath, projectBasePath)
        ?: throw IllegalStateException("Workspace root is not configured")
    val targets = resolveUnrealCommandTargets(
        uprojectPath = state.uprojectPath,
        selectedTargetTypes = state.selectedTargetTypes,
        discoveredTargets = state.discoveredTargets,
    )
    val platforms = normalizedCommandValues(state.selectedPlatforms)

    return targets.flatMap { target ->
        platforms.map { platform ->
            UnrealCommandContext(
                uprojectPath = uprojectPath,
                engineRoot = Path.of(state.engineRoot),
                workspaceRoot = workspaceRoot,
                packageDirectory = Path.of(settings.effectivePackageDirectory()),
                buildConfiguration = settings.effectiveBuildConfiguration(),
                targetName = target.name,
                targetType = target.type,
                platform = platform,
                extraArguments = state.activeCommandLine,
            )
        }
    }
}

internal fun resolveUnrealCommandTargets(
    uprojectPath: String,
    selectedTargetTypes: List<String>,
    discoveredTargets: List<UnrealTargetState>,
): List<UnrealResolvedCommandTarget> {
    val fallbackTargetName = fallbackTargetName(uprojectPath)

    return normalizedCommandValues(selectedTargetTypes).flatMap { selectedTargetType ->
        val matchingTargets = discoveredTargets.filter { it.type == selectedTargetType }
        if (matchingTargets.isEmpty()) {
            listOf(UnrealResolvedCommandTarget(fallbackTargetName, selectedTargetType))
        } else {
            matchingTargets.map { UnrealResolvedCommandTarget(it.name, it.type) }
        }
    }
}

internal fun buildCookPackageActionEnabled(state: UnrealHelperSettingsState): Boolean =
    buildCookPackageToolbarValidationError(state) == null

internal fun buildCookPackageValidationError(
    state: UnrealHelperSettingsState,
    projectBasePath: String?,
): String? =
    baseBuildCookPackageValidationError(state)
        ?: workspaceValidationError(state, projectBasePath)

private fun baseBuildCookPackageValidationError(state: UnrealHelperSettingsState): String? =
    when {
        state.uprojectPath.isBlank() -> ".uproject path is not configured"
        state.engineRoot.isBlank() ->
            "Engine root is not configured; set it in Tools > UnrealHelper before running Build, Cook, or Package."
        normalizedCommandValues(state.selectedTargetTypes).isEmpty() -> "No target types are selected"
        normalizedCommandValues(state.selectedPlatforms).isEmpty() -> "No platforms are selected"
        else -> null
    }

private fun buildCookPackageToolbarValidationError(state: UnrealHelperSettingsState): String? =
    when {
        state.uprojectPath.isBlank() -> ".uproject path is not configured"
        normalizedCommandValues(state.selectedTargetTypes).isEmpty() -> "No target types are selected"
        normalizedCommandValues(state.selectedPlatforms).isEmpty() -> "No platforms are selected"
        else -> null
    }

private fun workspaceValidationError(
    state: UnrealHelperSettingsState,
    projectBasePath: String?,
): String? {
    val uprojectPath = Path.of(state.uprojectPath)
    return if (workspaceRootPath(state, uprojectPath, projectBasePath) == null) {
        "Workspace root is not configured"
    } else {
        null
    }
}

private fun workspaceRootPath(
    state: UnrealHelperSettingsState,
    uprojectPath: Path,
    projectBasePath: String?,
): Path? =
    if (state.workspaceRoot.isNotBlank()) {
        Path.of(state.workspaceRoot)
    } else {
        uprojectPath.parent ?: projectBasePath?.takeIf { it.isNotBlank() }?.let(Path::of)
    }

private fun fallbackTargetName(uprojectPath: String): String {
    val fileName = Path.of(uprojectPath).fileName?.toString().orEmpty()
    val extensionStart = fileName.lastIndexOf('.')
    return if (extensionStart > 0) {
        fileName.substring(0, extensionStart)
    } else {
        fileName.ifBlank { "UnrealProject" }
    }
}

private fun normalizedCommandValues(values: List<String>): List<String> =
    values
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

private data class UnrealCommandIdentity(
    val commandLine: List<String>,
    val workingDirectory: String,
)
