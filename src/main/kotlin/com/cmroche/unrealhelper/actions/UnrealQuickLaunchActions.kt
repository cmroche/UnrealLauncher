package com.cmroche.unrealhelper.actions

import com.cmroche.unrealhelper.config.SelectedTargetPlatformConfigurationResult
import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.config.TargetPlatformConfigurationService
import com.cmroche.unrealhelper.config.TargetPlatformEntry
import com.cmroche.unrealhelper.launch.CookedExecutableResolver
import com.cmroche.unrealhelper.launch.QuickLaunchKey
import com.cmroche.unrealhelper.launch.QuickLaunchProcessService
import com.cmroche.unrealhelper.launch.QuickLaunchProfileState
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import java.nio.file.Path

internal class UnrealLaunchAction : DumbAwareAction("Launch", "Launch selected cooked Unreal targets", null) {
    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isVisible = project != null
        event.presentation.isEnabled = project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val settings = project.service<UnrealHelperSettings>()
        val selectedConfigurationResult = project.service<TargetPlatformConfigurationService>().selectedConfigurationResult()
        val selectedConfiguration = when (selectedConfigurationResult) {
            is SelectedTargetPlatformConfigurationResult.Valid -> selectedConfigurationResult.configuration
            else -> {
                UnrealActionMessages.selectedConfigurationError(selectedConfigurationResult)
                    ?.let { UnrealActionMessages.showError(project, it) }
                return
            }
        }

        val packageDirectory = settings.effectivePackageDirectory()
        val error = quickLaunchValidationError(settings.state, packageDirectory)
        if (error != null) {
            UnrealActionMessages.showError(project, error)
            return
        }

        try {
            val commands = createQuickLaunchCommands(
                state = settings.state,
                configuration = selectedConfiguration,
                packageDirectory = Path.of(packageDirectory),
            )

            if (commands.isEmpty()) {
                UnrealActionMessages.showError(
                    project,
                    "Could not resolve any cooked executables for selected configuration '${selectedConfiguration.name}'.",
                )
                return
            }

            val processService = project.service<QuickLaunchProcessService>()
            commands.forEach { command ->
                processService.launch(command.key, command.commandLine)
            }
        } catch (exception: ProcessCanceledException) {
            throw exception
        } catch (exception: Exception) {
            UnrealActionMessages.showError(
                project,
                "Failed to launch selected configuration '${selectedConfiguration.name}': " +
                    (exception.message ?: exception.javaClass.simpleName),
            )
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal class UnrealStopLaunchAction : DumbAwareAction("Stop", "Stop UnrealHelper quick launches", null) {
    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isVisible = project != null

        if (project == null) {
            event.presentation.isEnabled = false
            return
        }

        event.presentation.isEnabled = project.service<QuickLaunchProcessService>()
            .runningKeys()
            .isNotEmpty()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val processService = project.service<QuickLaunchProcessService>()
        val selectedConfigurationResult = project.service<TargetPlatformConfigurationService>().selectedConfigurationResult()
        val selectedKeys = when (selectedConfigurationResult) {
            is SelectedTargetPlatformConfigurationResult.Valid -> selectedQuickLaunchKeys(selectedConfigurationResult.configuration)
            else -> emptyList()
        }
        val selection = stopLaunchSelection(
            selectedKeys = selectedKeys,
            runningKeys = processService.runningKeys(),
        )

        if (selection.stopAll) {
            processService.stopAll()
        } else {
            selection.keys.forEach(processService::stop)
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal data class UnrealQuickLaunchCommand(
    val key: QuickLaunchKey,
    val commandLine: GeneralCommandLine,
)

internal data class UnrealStopLaunchSelection(
    val keys: Set<QuickLaunchKey>,
    val stopAll: Boolean,
)

internal fun createQuickLaunchCommands(
    state: UnrealHelperSettingsState,
    configuration: TargetPlatformConfiguration,
    packageDirectory: Path,
    resolveExecutable: (QuickLaunchProfileState, Path, String) -> Path? = { profile, packages, executableName ->
        CookedExecutableResolver.resolve(profile, packages, executableName)
    },
): List<UnrealQuickLaunchCommand> {
    if (quickLaunchValidationError(state, packageDirectory.toString()) != null) {
        return emptyList()
    }

    val uprojectPath = Path.of(state.uprojectPath)
    val unresolvedEntries = mutableListOf<String>()
    val commands = configuration.entries.mapIndexedNotNull { index, entry ->
        val profile = quickLaunchProfileForEntry(configuration, index, entry)
        val executableName = executableNameForQuickLaunch(state, entry.targetType.trim(), uprojectPath)
        val executable = resolveExecutable(profile, packageDirectory, executableName)
        if (executable == null) {
            unresolvedEntries += "entry ${index + 1} ${entry.targetType.trim()} ${entry.platform.trim()}"
            return@mapIndexedNotNull null
        }
        UnrealQuickLaunchCommand(
            key = QuickLaunchKey(
                configurationName = configuration.name,
                entryIndex = index,
                targetType = entry.targetType.trim(),
                platform = entry.platform.trim(),
            ),
            commandLine = CookedExecutableResolver.launchCommand(profile, executable, state.activeCommandLine),
        )
    }

    if (unresolvedEntries.isNotEmpty()) {
        throw IllegalStateException(
            "Could not resolve cooked executable for selected configuration '${configuration.name}': " +
                unresolvedEntries.joinToString(", ") + ".",
        )
    }

    return commands
}

internal fun selectedQuickLaunchKeys(configuration: TargetPlatformConfiguration): List<QuickLaunchKey> =
    configuration.entries.mapIndexed { index, entry ->
        QuickLaunchKey(
            configurationName = configuration.name,
            entryIndex = index,
            targetType = entry.targetType.trim(),
            platform = entry.platform.trim(),
        )
    }

internal fun stopLaunchSelection(
    selectedKeys: Collection<QuickLaunchKey>,
    runningKeys: Set<QuickLaunchKey>,
): UnrealStopLaunchSelection {
    val selectedRunningKeys = selectedKeys.toSet().intersect(runningKeys)
    return if (selectedRunningKeys.isNotEmpty()) {
        UnrealStopLaunchSelection(keys = selectedRunningKeys, stopAll = false)
    } else {
        UnrealStopLaunchSelection(keys = runningKeys, stopAll = true)
    }
}

internal fun quickLaunchValidationError(
    state: UnrealHelperSettingsState,
    packageDirectory: String,
): String? =
    when {
        state.uprojectPath.isBlank() -> ".uproject path is not configured"
        packageDirectory.isBlank() ->
            "Package directory is not configured; set it in Tools > UnrealHelper before launching cooked builds."
        else -> null
    }

private fun quickLaunchProfileForEntry(
    configuration: TargetPlatformConfiguration,
    entryIndex: Int,
    entry: TargetPlatformEntry,
): QuickLaunchProfileState =
    QuickLaunchProfileState(
        name = "${configuration.name} ${entryIndex + 1}: ${entry.targetType.trim()} ${entry.platform.trim()}",
        targetType = entry.targetType.trim(),
        platform = entry.platform.trim(),
        executablePath = entry.executablePath.trim(),
        workingDirectory = entry.workingDirectory.trim(),
        arguments = entry.arguments.trim(),
    )

private fun executableNameForQuickLaunch(
    state: UnrealHelperSettingsState,
    targetType: String,
    uprojectPath: Path,
): String {
    val projectName = CookedExecutableResolver.projectName(uprojectPath)
    val matchingTarget = state.discoveredTargets.firstOrNull {
        it.type == targetType && it.usesUniqueBuildEnvironment && it.name.isNotBlank()
    }
    return matchingTarget?.name ?: projectName
}
