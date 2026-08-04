package com.cmroche.unrealhelper.command

import com.cmroche.unrealhelper.workflow.UnrealCookMode
import com.cmroche.unrealhelper.workflow.artifactCookDirectory
import com.cmroche.unrealhelper.workflow.artifactStageDirectory
import java.nio.file.Path

object UnrealCommandBuilder {
    fun buildBatch(
        contexts: List<UnrealCommandContext>,
        osName: String = System.getProperty("os.name"),
    ): UnrealCommand {
        require(contexts.isNotEmpty())
        val targets = contexts.distinctBy { it.artifact }.map { context ->
            "-Target=${context.targetName} ${context.platform} ${context.buildConfiguration} " +
                "-Project=${quoteNested(context.uprojectPath.toString())}"
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
        outputDirectory: Path = artifactCookDirectory(context.artifact),
        osName: String = System.getProperty("os.name"),
    ): UnrealCommand =
        uatCommand(
            title = "Unreal Cook ${context.targetName} ${context.targetType} ${context.platform}",
            context = context,
            osName = osName,
            phaseArguments = buildList {
                addAll(listOf("-skipbuild", "-cook", "-skipstage", "-skippackage"))
                if (mode == UnrealCookMode.INCREMENTAL) add("-cookincremental")
                add("-CookOutputDir=$outputDirectory")
            },
        )

    fun stage(
        context: UnrealCommandContext,
        cookOutputDirectory: Path = artifactCookDirectory(context.artifact),
        stagingDirectory: Path = artifactStageDirectory(context.artifact),
        osName: String = System.getProperty("os.name"),
    ): UnrealCommand =
        uatCommand(
            title = "Unreal Stage ${context.targetName} ${context.targetType} ${context.platform}",
            context = context,
            osName = osName,
            phaseArguments = listOf(
                "-skipbuild", "-skipcook", "-stage", "-skippackage",
                "-CookOutputDir=$cookOutputDirectory", "-stagingdirectory=$stagingDirectory",
            ),
        )

    fun packageProject(
        context: UnrealCommandContext,
        cookOutputDirectory: Path = artifactCookDirectory(context.artifact),
        stagingDirectory: Path = artifactStageDirectory(context.artifact),
        archiveDirectory: Path = context.packageDirectory,
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
                "-CookOutputDir=$cookOutputDirectory",
                "-stagingdirectory=$stagingDirectory",
                "-package",
                "-pak",
                "-archive",
                "-archivedirectory=$archiveDirectory",
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
        } else buildList {
            if (context.targetType == "Client") add("-client")
            add("-targetplatform=${context.platform}")
            add("-clientconfig=${context.buildConfiguration}")
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

    private fun quoteNested(value: String): String = buildString {
        append('"')
        var backslashes = 0
        value.forEach { character ->
            when (character) {
                '\\' -> backslashes++
                '"' -> {
                    repeat(backslashes * 2 + 1) { append('\\') }
                    append('"')
                    backslashes = 0
                }
                else -> {
                    repeat(backslashes) { append('\\') }
                    backslashes = 0
                    append(character)
                }
            }
        }
        repeat(backslashes * 2) { append('\\') }
        append('"')
    }
}
