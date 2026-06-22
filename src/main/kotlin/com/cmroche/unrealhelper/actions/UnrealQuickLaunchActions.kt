package com.cmroche.unrealhelper.actions

import com.cmroche.unrealhelper.launch.CookedExecutableResolver
import com.cmroche.unrealhelper.launch.QuickLaunchKey
import com.cmroche.unrealhelper.launch.QuickLaunchProcessService
import com.cmroche.unrealhelper.launch.QuickLaunchProfileState
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import java.nio.file.Path
import javax.swing.JComponent

internal class UnrealLaunchAction : ComboBoxAction(), DumbAware {
    init {
        templatePresentation.text = "Launch"
        templatePresentation.description = "Launch selected cooked Unreal targets"
    }

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isVisible = project != null

        if (project == null) {
            event.presentation.isEnabled = false
            return
        }

        val settings = project.service<UnrealHelperSettings>()
        event.presentation.isEnabled = quickLaunchValidationError(
            state = settings.state,
            packageDirectory = settings.effectivePackageDirectory(),
        ) == null
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return DefaultActionGroup()
        val settings = project.service<UnrealHelperSettings>()
        val packageDirectory = settings.effectivePackageDirectory()
        val packagePath = Path.of(packageDirectory)

        return DefaultActionGroup().also { group ->
            createQuickLaunchOptions(settings.state, packagePath).forEach { option ->
                group.add(UnrealLaunchOptionAction(project, option))
            }
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
        val settings = project.service<UnrealHelperSettings>()
        val processService = project.service<QuickLaunchProcessService>()
        val selection = stopLaunchSelection(
            selectedKeys = selectedQuickLaunchKeys(settings.state),
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

private class UnrealLaunchOptionAction(
    private val project: Project,
    private val option: UnrealQuickLaunchOption,
) : DumbAwareAction(option.text) {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = option.isEnabled
    }

    override fun actionPerformed(event: AnActionEvent) {
        if (!option.isEnabled) return

        val settings = project.service<UnrealHelperSettings>()
        executeQuickLaunchOption(
            option = option,
            state = settings.state,
            packageDirectory = settings.effectivePackageDirectory(),
            launch = { key, commandLine ->
                project.service<QuickLaunchProcessService>().launch(key, commandLine)
            },
            notifyError = { notifyLaunchError(project, it) },
        )
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal data class UnrealQuickLaunchOption(
    val key: QuickLaunchKey,
    val text: String,
    val executable: Path?,
) {
    val isEnabled: Boolean
        get() = executable != null
}

internal data class UnrealQuickLaunchCommand(
    val key: QuickLaunchKey,
    val commandLine: GeneralCommandLine,
)

internal data class UnrealStopLaunchSelection(
    val keys: Set<QuickLaunchKey>,
    val stopAll: Boolean,
)

internal fun createQuickLaunchOptions(
    state: UnrealHelperSettingsState,
    packageDirectory: Path,
    resolveExecutable: (QuickLaunchProfileState, Path, Path) -> Path? = { profile, packages, uproject ->
        CookedExecutableResolver.resolve(profile, packages, uproject)
    },
): List<UnrealQuickLaunchOption> {
    if (quickLaunchValidationError(state, packageDirectory.toString()) != null) {
        return emptyList()
    }

    val uprojectPath = Path.of(state.uprojectPath)
    return selectedQuickLaunchKeys(state).map { key ->
        val profile = quickLaunchProfileForOption(state, key)
        val executable = resolveExecutable(profile, packageDirectory, uprojectPath)
        UnrealQuickLaunchOption(
            key = key,
            text = launchOptionText(key, executable),
            executable = executable,
        )
    }
}

internal fun createQuickLaunchCommand(
    state: UnrealHelperSettingsState,
    key: QuickLaunchKey,
    packageDirectory: Path,
    resolveExecutable: (QuickLaunchProfileState, Path, Path) -> Path? = { profile, packages, uproject ->
        CookedExecutableResolver.resolve(profile, packages, uproject)
    },
): UnrealQuickLaunchCommand? {
    if (quickLaunchValidationError(state, packageDirectory.toString()) != null) {
        return null
    }

    val profile = quickLaunchProfileForOption(state, key)
    val uprojectPath = Path.of(state.uprojectPath)
    val executable = resolveExecutable(profile, packageDirectory, uprojectPath) ?: return null

    return UnrealQuickLaunchCommand(
        key = key,
        commandLine = CookedExecutableResolver.launchCommand(profile, executable, state.activeCommandLine),
    )
}

internal fun executeQuickLaunchOption(
    option: UnrealQuickLaunchOption,
    state: UnrealHelperSettingsState,
    packageDirectory: String,
    launch: (QuickLaunchKey, GeneralCommandLine) -> Unit,
    notifyError: (String) -> Unit,
    resolveExecutable: (QuickLaunchProfileState, Path, Path) -> Path? = { profile, packages, uproject ->
        CookedExecutableResolver.resolve(profile, packages, uproject)
    },
) {
    if (!option.isEnabled) return

    try {
        val command = createQuickLaunchCommand(
            state = state,
            key = option.key,
            packageDirectory = Path.of(packageDirectory),
            resolveExecutable = resolveExecutable,
        )

        if (command == null) {
            notifyError("Could not resolve cooked executable for ${option.key.label()} under $packageDirectory.")
            return
        }

        launch(command.key, command.commandLine)
    } catch (exception: ProcessCanceledException) {
        throw exception
    } catch (exception: Exception) {
        notifyError("Failed to launch ${option.key.label()}: ${exception.message ?: exception.javaClass.simpleName}")
    }
}

internal fun selectedQuickLaunchKeys(state: UnrealHelperSettingsState): List<QuickLaunchKey> {
    val targetTypes = normalizedQuickLaunchValues(state.selectedTargetTypes)
    val platforms = normalizedQuickLaunchValues(state.selectedPlatforms)

    return targetTypes.flatMap { targetType ->
        platforms.map { platform ->
            QuickLaunchKey(targetType = targetType, platform = platform)
        }
    }
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
        normalizedQuickLaunchValues(state.selectedTargetTypes).isEmpty() -> "No target types are selected"
        normalizedQuickLaunchValues(state.selectedPlatforms).isEmpty() -> "No platforms are selected"
        else -> null
    }

private fun quickLaunchProfileForOption(
    state: UnrealHelperSettingsState,
    key: QuickLaunchKey,
): QuickLaunchProfileState =
    state.quickLaunchProfiles.firstOrNull {
        it.targetType.trim() == key.targetType && it.platform.trim() == key.platform
    } ?: defaultQuickLaunchProfile(key)

private fun defaultQuickLaunchProfile(key: QuickLaunchKey): QuickLaunchProfileState =
    QuickLaunchProfileState(
        name = key.label(),
        targetType = key.targetType,
        platform = key.platform,
    )

private fun normalizedQuickLaunchValues(values: List<String>): List<String> =
    values
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

private fun launchOptionText(key: QuickLaunchKey, executable: Path?): String =
    if (executable == null) {
        "Launch ${key.label()} (cooked executable not found)"
    } else {
        "Launch ${key.label()}"
    }

private fun QuickLaunchKey.label(): String =
    "$targetType $platform"

private fun notifyLaunchError(project: Project, message: String) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("UnrealHelper")
        .createNotification(message, NotificationType.ERROR)
        .notify(project)
}
