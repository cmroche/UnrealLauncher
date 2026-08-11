package com.cmroche.unrealhelper.discovery

import java.nio.file.Files
import java.nio.file.Path

enum class UnrealTargetType {
    Game,
    Client,
    Server,
    Editor,
}

data class DiscoveredUnrealTarget(
    val name: String,
    val type: UnrealTargetType,
)

data class UnrealProjectDiscoveryResult(
    val workspaceRoot: String?,
    val uprojectPath: String?,
    val engineRoot: String?,
    val targets: List<DiscoveredUnrealTarget>,
    val platforms: List<String>,
    val warnings: List<String>,
)

object UnrealProjectDiscovery {
    private val targetClassRegex = Regex(
        """\bclass\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([A-Za-z_][A-Za-z0-9_]*)""",
    )
    private val targetTypeRegex = Regex("""TargetType\.(Game|Client|Server|Editor)\b""")

    fun fromRiderModel(
        uprojectPath: Path?,
        engineRoot: Path?,
        targetFiles: Collection<Path>,
        platforms: Collection<String>,
        modelWarnings: Collection<String> = emptyList(),
    ): UnrealProjectDiscoveryResult {
        val warnings = modelWarnings.toMutableList()
        val normalizedProject = uprojectPath?.toAbsolutePath()?.normalize()
        val workspaceRoot = normalizedProject?.parent

        if (normalizedProject == null) {
            warnings += "Rider did not provide an Unreal project file."
        }

        val targets = if (workspaceRoot == null) {
            emptyList()
        } else {
            discoverTargets(workspaceRoot, targetFiles, warnings)
        }
        val normalizedPlatforms = platforms
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()

        if (targets.isEmpty()) {
            warnings += "Rider did not provide any Unreal target files."
        }
        if (normalizedPlatforms.isEmpty()) {
            warnings += "Rider did not provide any Unreal target platforms."
        }

        return UnrealProjectDiscoveryResult(
            workspaceRoot = workspaceRoot?.toString(),
            uprojectPath = normalizedProject?.toString(),
            engineRoot = engineRoot?.toAbsolutePath()?.normalize()?.toString(),
            targets = targets,
            platforms = normalizedPlatforms,
            warnings = warnings.distinct(),
        )
    }

    private fun discoverTargets(
        workspaceRoot: Path,
        targetFiles: Collection<Path>,
        warnings: MutableList<String>,
    ): List<DiscoveredUnrealTarget> {
        val parsedTargets = targetFiles
            .map { path -> if (path.isAbsolute) path.normalize() else workspaceRoot.resolve(path).normalize() }
            .filter { it.fileName.toString().endsWith(".Target.cs", ignoreCase = true) }
            .distinct()
            .mapNotNull { parseTargetFile(it, warnings) }
        val targetsByClassName = parsedTargets.associateBy { it.className }

        return parsedTargets
            .map { target ->
                DiscoveredUnrealTarget(
                    name = target.name,
                    type = resolveTargetType(target, targetsByClassName),
                )
            }
            .distinctBy { it.name to it.type }
            .sortedWith(compareBy<DiscoveredUnrealTarget> { it.type.ordinal }.thenBy { it.name })
    }

    private fun parseTargetFile(
        targetFile: Path,
        warnings: MutableList<String>,
    ): ParsedUnrealTarget? {
        val text = runCatching { Files.readString(targetFile) }
            .getOrElse {
                warnings += "Could not read Rider target file $targetFile: ${it.message}"
                return null
            }
        val targetName = targetFile.fileName.toString().removeSuffix(".Target.cs")
        val expectedClassName = "${targetName}Target"
        val classMatch = targetClassRegex.findAll(text)
            .firstOrNull { it.groupValues[1] == expectedClassName }
            ?: targetClassRegex.find(text)

        return ParsedUnrealTarget(
            name = targetName,
            className = classMatch?.groupValues?.get(1) ?: expectedClassName,
            baseClassName = classMatch?.groupValues?.get(2),
            explicitType = targetTypeRegex.find(text)?.groupValues?.get(1)?.let(UnrealTargetType::valueOf),
        )
    }

    private fun resolveTargetType(
        target: ParsedUnrealTarget,
        targetsByClassName: Map<String, ParsedUnrealTarget>,
        visited: Set<String> = emptySet(),
    ): UnrealTargetType {
        target.explicitType?.let { return it }
        if (target.className in visited) return inferTargetType(target.name)
        val baseTarget = target.baseClassName?.let(targetsByClassName::get)
        return baseTarget?.let {
            resolveTargetType(it, targetsByClassName, visited + target.className)
        } ?: inferTargetType(target.name)
    }

    private fun inferTargetType(targetName: String): UnrealTargetType =
        when {
            targetName.endsWith("Server", ignoreCase = true) -> UnrealTargetType.Server
            targetName.endsWith("Client", ignoreCase = true) -> UnrealTargetType.Client
            targetName.endsWith("Editor", ignoreCase = true) -> UnrealTargetType.Editor
            else -> UnrealTargetType.Game
        }

    private data class ParsedUnrealTarget(
        val name: String,
        val className: String,
        val baseClassName: String?,
        val explicitType: UnrealTargetType?,
    )
}
