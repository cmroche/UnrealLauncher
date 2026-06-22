package com.cmroche.unrealhelper.command

import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Path

object UnrealCommandBuilder {
    fun build(
        context: UnrealCommandContext,
        osName: String = System.getProperty("os.name"),
    ): UnrealCommand =
        UnrealCommand(
            title = "Unreal Build ${context.targetName} ${context.targetType} ${context.platform}",
            executable = unrealBuildTool(context.engineRoot, osName),
            arguments = listOf(
                context.targetName,
                context.platform,
                context.buildConfiguration,
                "-Project=${context.uprojectPath}",
                "-WaitMutex",
            ) + parseExtraArguments(context.extraArguments),
            workingDirectory = context.workspaceRoot.toString(),
        )

    fun cook(
        context: UnrealCommandContext,
        osName: String = System.getProperty("os.name"),
    ): UnrealCommand =
        UnrealCommand(
            title = "Unreal Cook ${context.targetName} ${context.targetType} ${context.platform}",
            executable = runUat(context.engineRoot, osName),
            arguments = listOf(
                "BuildCookRun",
                "-project=${context.uprojectPath}",
                "-noP4",
                "-cook",
                "-skipstage",
                "-skippackage",
                "-targetplatform=${context.platform}",
                "-clientconfig=${context.buildConfiguration}",
                "-utf8output",
            ) + parseExtraArguments(context.extraArguments),
            workingDirectory = context.workspaceRoot.toString(),
        )

    fun packageProject(
        context: UnrealCommandContext,
        osName: String = System.getProperty("os.name"),
    ): UnrealCommand =
        UnrealCommand(
            title = "Unreal Package ${context.targetName} ${context.targetType} ${context.platform}",
            executable = runUat(context.engineRoot, osName),
            arguments = listOf(
                "BuildCookRun",
                "-project=${context.uprojectPath}",
                "-noP4",
                "-build",
                "-cook",
                "-stage",
                "-pak",
                "-archive",
                "-archivedirectory=${context.packageDirectory}",
                "-targetplatform=${context.platform}",
                "-clientconfig=${context.buildConfiguration}",
                "-utf8output",
            ) + parseExtraArguments(context.extraArguments),
            workingDirectory = context.workspaceRoot.toString(),
        )

    fun parseExtraArguments(extraArguments: String): List<String> =
        ParametersListUtil.parse(extraArguments)
            .filter { it.isNotEmpty() }

    private fun unrealBuildTool(engineRoot: Path, osName: String): String =
        engineRoot.resolve("Engine")
            .resolve("Binaries")
            .resolve("DotNET")
            .resolve("UnrealBuildTool")
            .resolve(if (isWindows(osName)) "UnrealBuildTool.exe" else "UnrealBuildTool")
            .toString()

    private fun runUat(engineRoot: Path, osName: String): String =
        engineRoot.resolve("Engine")
            .resolve("Build")
            .resolve("BatchFiles")
            .resolve(if (isWindows(osName)) "RunUAT.bat" else "RunUAT.sh")
            .toString()

    private fun isWindows(osName: String): Boolean =
        osName.startsWith("Windows", ignoreCase = true)
}
