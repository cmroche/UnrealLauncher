package com.cmroche.unrealhelper.launch

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Files
import java.nio.file.Path

object CookedExecutableResolver {
    fun resolve(
        profile: QuickLaunchProfileState,
        packageDirectory: Path,
        projectName: String,
    ): Path? {
        profile.executablePath.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { Path.of(it) }
            ?.takeIf { Files.isRegularFile(it) }
            ?.let { return it }

        return candidatePaths(packageDirectory, profile.platform, projectName)
            .firstOrNull { Files.isRegularFile(it) }
    }

    fun resolve(
        profile: QuickLaunchProfileState,
        packageDirectory: Path,
        uprojectPath: Path,
    ): Path? =
        resolve(profile, packageDirectory, projectName(uprojectPath))

    fun projectName(uprojectPath: Path): String {
        val fileName = uprojectPath.fileName?.toString().orEmpty()
        return if (fileName.endsWith(UPROJECT_EXTENSION, ignoreCase = true)) {
            fileName.dropLast(UPROJECT_EXTENSION.length)
        } else {
            fileName.substringBeforeLast(".", fileName)
        }
    }

    fun launchCommand(
        profile: QuickLaunchProfileState,
        executable: Path,
        globalArgs: String,
    ): GeneralCommandLine {
        val commandLine = GeneralCommandLine(executable.toString())
        workingDirectory(profile, executable)?.let { commandLine.withWorkingDirectory(it) }
        commandLine.parametersList.addAll(parseArguments(profile.arguments) + parseArguments(globalArgs))
        return commandLine
    }

    internal fun candidatePaths(packageDirectory: Path, platform: String, projectName: String): List<Path> =
        when {
            platform.equals("Win64", ignoreCase = true) -> listOf(
                packageDirectory.resolve("Windows").resolve("$projectName.exe"),
                packageDirectory.resolve("WindowsNoEditor").resolve("$projectName.exe"),
            )

            platform.equals("Mac", ignoreCase = true) -> listOf(
                packageDirectory.resolve("Mac")
                    .resolve("$projectName.app")
                    .resolve("Contents")
                    .resolve("MacOS")
                    .resolve(projectName),
            )

            platform.equals("Linux", ignoreCase = true) -> listOf(
                packageDirectory.resolve("Linux").resolve(projectName),
            )

            else -> listOf(
                packageDirectory.resolve(platform.trim()).resolve(projectName),
            )
        }

    private fun workingDirectory(profile: QuickLaunchProfileState, executable: Path): Path? =
        profile.workingDirectory.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { Path.of(it) }
            ?: executable.parent

    private fun parseArguments(arguments: String): List<String> =
        ParametersListUtil.parse(arguments)
            .filter { it.isNotEmpty() }

    private const val UPROJECT_EXTENSION = ".uproject"
}
