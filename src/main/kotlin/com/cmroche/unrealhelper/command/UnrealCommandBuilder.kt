package com.cmroche.unrealhelper.command

import com.cmroche.unrealhelper.workflow.UnrealCookMode
import java.nio.file.Path

object UnrealCommandBuilder {
    fun buildBatch(
        contexts: List<UnrealCommandContext>,
        osName: String = System.getProperty("os.name"),
    ): UnrealCommand {
        require(contexts.isNotEmpty())
        val targets = contexts.distinctBy { it.artifact }.map { context ->
            "-Target=${context.targetName} ${context.platform} ${context.buildConfiguration} " +
                "-Project=${context.uprojectPath}"
        }
        val first = contexts.first()
        return UnrealCommand(
            title = "Unreal Build ${targets.size} target(s)",
            executable = unrealBuildTool(first.engineRoot, osName),
            arguments = targets + "-WaitMutex",
            workingDirectory = first.workspaceRoot.toString(),
        )
    }

    fun cook(
        context: UnrealCommandContext,
        mode: UnrealCookMode,
        osName: String = System.getProperty("os.name"),
    ): UnrealCommand =
        uatCommand(
            title = "Unreal Cook ${context.targetName} ${context.targetType} ${context.platform}",
            context = context,
            osName = osName,
            phaseArguments = buildList {
                addAll(listOf("-skipbuild", "-cook", "-skipstage", "-skippackage"))
                if (mode == UnrealCookMode.INCREMENTAL) add("-cookincremental")
            },
        )

    fun stage(
        context: UnrealCommandContext,
        osName: String = System.getProperty("os.name"),
    ): UnrealCommand =
        uatCommand(
            title = "Unreal Stage ${context.targetName} ${context.targetType} ${context.platform}",
            context = context,
            osName = osName,
            phaseArguments = listOf("-skipbuild", "-skipcook", "-stage", "-skippackage"),
        )

    fun packageProject(
        context: UnrealCommandContext,
        osName: String = System.getProperty("os.name"),
    ): UnrealCommand =
        uatCommand(
            title = "Unreal Package ${context.targetName} ${context.targetType} ${context.platform}",
            context = context,
            osName = osName,
            phaseArguments = listOf(
                "-skipbuild",
                "-skipcook",
                "-skipstage",
                "-package",
                "-pak",
                "-archive",
                "-archivedirectory=${context.packageDirectory}",
            ),
        )

    private fun uatCommand(
        title: String,
        context: UnrealCommandContext,
        osName: String,
        phaseArguments: List<String>,
    ): UnrealCommand =
        UnrealCommand(
            title = title,
            executable = runUat(context.engineRoot, osName),
            arguments = listOf(
                "BuildCookRun",
                "-project=${context.uprojectPath}",
                "-target=${context.targetName}",
                "-noP4",
                "-utf8output",
            ) + phaseArguments + roleArguments(context),
            workingDirectory = context.workspaceRoot.toString(),
        )

    private fun roleArguments(context: UnrealCommandContext): List<String> =
        if (context.targetType == "Server") {
            listOf(
                "-server",
                "-noclient",
                "-servertargetplatform=${context.platform}",
                "-serverconfig=${context.buildConfiguration}",
            )
        } else {
            listOf(
                "-targetplatform=${context.platform}",
                "-clientconfig=${context.buildConfiguration}",
            )
        }

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
