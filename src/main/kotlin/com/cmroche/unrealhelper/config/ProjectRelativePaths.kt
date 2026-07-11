package com.cmroche.unrealhelper.config

import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import java.nio.file.Path
import java.nio.file.Paths

internal object ProjectRelativePaths {
    fun projectRoot(state: UnrealHelperSettingsState): Path? {
        val workspaceRoot = state.workspaceRoot.trim()
        if (workspaceRoot.isNotEmpty()) {
            return Paths.get(workspaceRoot).normalize()
        }

        val uprojectPath = state.uprojectPath.trim()
        if (uprojectPath.isNotEmpty()) {
            return Paths.get(uprojectPath).parent?.normalize()
        }

        return null
    }

    fun resolveForUse(projectRoot: Path?, storedPath: String): String {
        val trimmed = storedPath.trim()
        if (trimmed.isEmpty()) {
            return ""
        }

        val path = Paths.get(trimmed)
        return if (path.isAbsolute || projectRoot == null) {
            path.normalize().toString()
        } else {
            projectRoot.resolve(path).normalize().toString()
        }
    }

    fun storeRelativeTo(projectRoot: Path?, pathText: String): String {
        val trimmed = pathText.trim()
        if (trimmed.isEmpty()) {
            return ""
        }

        val path = Paths.get(trimmed).normalize()
        if (projectRoot == null) {
            return path.toString()
        }

        return if (path.isAbsolute) {
            runCatching { projectRoot.normalize().relativize(path) }
                .getOrElse { path }
                .toString()
        } else {
            path.toString()
        }
    }
}

internal fun TargetPlatformEntry.withProjectRelativePaths(projectRoot: Path?): TargetPlatformEntry =
    copy(
        executablePath = ProjectRelativePaths.storeRelativeTo(projectRoot, executablePath),
        workingDirectory = ProjectRelativePaths.storeRelativeTo(projectRoot, workingDirectory),
    )

internal fun TargetPlatformConfiguration.withProjectRelativePaths(projectRoot: Path?): TargetPlatformConfiguration =
    copy(entries = entries.map { it.withProjectRelativePaths(projectRoot) })

internal fun TargetPlatformConfigurationsFile.withProjectRelativePaths(projectRoot: Path?): TargetPlatformConfigurationsFile =
    copy(configurations = configurations.map { it.withProjectRelativePaths(projectRoot) })
