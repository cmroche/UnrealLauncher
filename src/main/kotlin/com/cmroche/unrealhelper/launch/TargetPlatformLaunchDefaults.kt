package com.cmroche.unrealhelper.launch

import com.cmroche.unrealhelper.config.ProjectRelativePaths
import com.cmroche.unrealhelper.config.TargetPlatformEntry
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import java.nio.file.Path
import java.nio.file.Paths

internal fun TargetPlatformEntry.withDerivedLaunchPaths(
    state: UnrealHelperSettingsState,
    packageDirectory: Path,
): TargetPlatformEntry {
    if (state.uprojectPath.isBlank()) {
        return this
    }

    val projectRoot = ProjectRelativePaths.projectRoot(state)
    val defaultExecutable = CookedExecutableResolver.candidatePaths(
        packageDirectory = packageDirectory,
        platform = platform.trim(),
        projectName = executableNameForLaunch(state, targetType.trim(), Paths.get(state.uprojectPath)),
    ).firstOrNull()
    val effectiveExecutable = executablePath.trim()
        .takeIf { it.isNotEmpty() }
        ?.let { ProjectRelativePaths.resolveForUse(projectRoot, it) }
        ?.let(Paths::get)
        ?: defaultExecutable
    val defaultWorkingDirectory = effectiveExecutable?.parent

    return copy(
        executablePath = executablePath.trim()
            .ifEmpty { defaultExecutable?.let { ProjectRelativePaths.storeRelativeTo(projectRoot, it.toString()) }.orEmpty() },
        workingDirectory = workingDirectory.trim()
            .ifEmpty { defaultWorkingDirectory?.let { ProjectRelativePaths.storeRelativeTo(projectRoot, it.toString()) }.orEmpty() },
    )
}

internal fun executableNameForLaunch(
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
