package com.cmroche.unrealhelper.discovery

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.stream.Collectors

enum class UnrealTargetType {
    Game,
    Client,
    Server,
}

data class DiscoveredUnrealTarget(
    val name: String,
    val type: UnrealTargetType,
    val path: String,
    val usesUniqueBuildEnvironment: Boolean = false,
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
    private const val MaxScanDepth = 8
    private val excludedPathNames = setOf(
        ".git",
        ".gradle",
        ".idea",
        ".intellijPlatform",
        "Binaries",
        "Build",
        "DerivedDataCache",
        "Intermediate",
        "Saved",
    )
    private val platformOrder = listOf(
        "Win64",
        "Mac",
        "Linux",
        "LinuxArm64",
        "PS5",
        "Xbox",
        "XSX",
        "Android",
        "IOS",
    )
    private val targetClassRegex = Regex("""class\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*TargetRules""")
    private val targetTypeRegex = Regex("""TargetType\.(Game|Client|Server)\b""")
    private val platformReferenceRegex = Regex("""UnrealTargetPlatform\.([A-Za-z0-9_]+)""")

    fun discover(projectBasePath: Path): UnrealProjectDiscoveryResult {
        val warnings = mutableListOf<String>()
        val basePath = projectBasePath.toAbsolutePath().normalize()

        if (!Files.exists(basePath)) {
            return UnrealProjectDiscoveryResult(
                workspaceRoot = null,
                uprojectPath = null,
                engineRoot = null,
                targets = emptyList(),
                platforms = emptyList(),
                warnings = listOf("Project path does not exist: $basePath"),
            )
        }

        val uprojectFiles = findFiles(basePath, MaxScanDepth) { path ->
            path.fileName.toString().endsWith(".uproject", ignoreCase = true)
        }
        val selectedUproject = uprojectFiles.firstOrNull()
        if (uprojectFiles.size > 1) {
            warnings += "Multiple .uproject files found; using ${selectedUproject?.fileName}."
        }
        if (selectedUproject == null) {
            warnings += "No .uproject file found under $basePath."
        }

        val workspaceRoot = selectedUproject?.parent ?: basePath
        val targets = discoverTargets(workspaceRoot, warnings)
        val platforms = discoverPlatforms(workspaceRoot, warnings)

        return UnrealProjectDiscoveryResult(
            workspaceRoot = workspaceRoot.toString(),
            uprojectPath = selectedUproject?.toString(),
            engineRoot = discoverEngineRoot(workspaceRoot)?.toString(),
            targets = targets,
            platforms = platforms,
            warnings = warnings,
        )
    }

    private fun discoverTargets(workspaceRoot: Path, warnings: MutableList<String>): List<DiscoveredUnrealTarget> {
        val sourceRoot = workspaceRoot.resolve("Source")
        if (!Files.isDirectory(sourceRoot)) {
            warnings += "No Source directory found under $workspaceRoot."
            return emptyList()
        }

        val targetFiles = findFiles(sourceRoot, MaxScanDepth) { path ->
            path.fileName.toString().endsWith(".Target.cs", ignoreCase = true)
        }
        if (targetFiles.isEmpty()) {
            warnings += "No Unreal target files found under $sourceRoot."
            return emptyList()
        }

        return targetFiles
            .mapNotNull { parseTargetFile(workspaceRoot, it, warnings) }
            .distinctBy { it.name to it.type }
            .sortedWith(compareBy<DiscoveredUnrealTarget> { it.type.ordinal }.thenBy { it.name })
    }

    private fun parseTargetFile(
        workspaceRoot: Path,
        targetFile: Path,
        warnings: MutableList<String>,
    ): DiscoveredUnrealTarget? {
        val text = runCatching { Files.readString(targetFile) }
            .getOrElse {
                warnings += "Could not read target file $targetFile: ${it.message}"
                return null
            }
        val className = targetClassRegex.find(text)?.groupValues?.get(1)
        val targetName = className?.removeSuffix("Target")
            ?: targetFile.fileName.toString().removeSuffix(".Target.cs")
        val targetType = targetTypeRegex.find(text)?.groupValues?.get(1)?.let(UnrealTargetType::valueOf)
            ?: inferTargetType(targetName)

        return DiscoveredUnrealTarget(
            name = targetName,
            type = targetType,
            path = workspaceRoot.relativize(targetFile).toString(),
            usesUniqueBuildEnvironment = usesUniqueBuildEnvironment(text),
        )
    }

    private fun usesUniqueBuildEnvironment(text: String): Boolean =
        Regex("""\bBuildEnvironment\s*=\s*TargetBuildEnvironment\.Unique\b""").containsMatchIn(text)

    private fun discoverEngineRoot(workspaceRoot: Path): Path? =
        generateSequence(workspaceRoot.toAbsolutePath().normalize()) { it.parent }
            .firstOrNull(::looksLikeEngineRoot)

    private fun looksLikeEngineRoot(path: Path): Boolean =
        Files.isDirectory(
            path.resolve("Engine")
                .resolve("Binaries")
                .resolve("DotNET")
                .resolve("UnrealBuildTool"),
        ) &&
            Files.isDirectory(
                path.resolve("Engine")
                    .resolve("Build")
                    .resolve("BatchFiles"),
            )

    private fun inferTargetType(targetName: String): UnrealTargetType =
        when {
            targetName.endsWith("Server", ignoreCase = true) -> UnrealTargetType.Server
            targetName.endsWith("Client", ignoreCase = true) -> UnrealTargetType.Client
            else -> UnrealTargetType.Game
        }

    private fun discoverPlatforms(workspaceRoot: Path, warnings: MutableList<String>): List<String> {
        val discovered = mutableListOf<String>()

        discovered += discoverPlatformDirectories(workspaceRoot.resolve("Platforms"))
        discovered += discoverPlatformDirectories(workspaceRoot.resolve("Config"))
        discovered += discoverPlatformReferences(workspaceRoot.resolve("Source"))

        val normalized = discovered
            .mapNotNull(::normalizePlatformName)
            .distinct()
            .sortedWith(compareBy<String> { platformOrder.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }.thenBy { it })

        if (normalized.isNotEmpty()) {
            return normalized
        }

        warnings += "No supported platforms detected from project files; using host platform fallback."
        return listOf(hostPlatform())
    }

    private fun discoverPlatformDirectories(path: Path): List<String> {
        if (!Files.isDirectory(path)) return emptyList()

        return Files.list(path).use { stream ->
            stream
                .filter(Files::isDirectory)
                .map { it.fileName.toString() }
                .collect(Collectors.toList())
        }
    }

    private fun discoverPlatformReferences(sourceRoot: Path): List<String> {
        if (!Files.isDirectory(sourceRoot)) return emptyList()

        return findFiles(sourceRoot, MaxScanDepth) { path ->
            path.fileName.toString().endsWith(".cs", ignoreCase = true)
        }.flatMap { path ->
            runCatching { Files.readString(path) }
                .getOrDefault("")
                .let { text -> platformReferenceRegex.findAll(text).map { it.groupValues[1] }.toList() }
        }
    }

    private fun normalizePlatformName(name: String): String? =
        when (name.lowercase(Locale.ROOT)) {
            "windows", "win64" -> "Win64"
            "mac", "macos" -> "Mac"
            "linux" -> "Linux"
            "linuxarm64" -> "LinuxArm64"
            "ps5" -> "PS5"
            "xbox" -> "Xbox"
            "xsx" -> "XSX"
            "android" -> "Android"
            "ios" -> "IOS"
            else -> name.takeIf { it.isNotBlank() }
        }

    private fun hostPlatform(): String {
        val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
        return when {
            osName.contains("win") -> "Win64"
            osName.contains("mac") -> "Mac"
            osName.contains("linux") -> "Linux"
            else -> "Win64"
        }
    }

    private fun findFiles(root: Path, maxDepth: Int, predicate: (Path) -> Boolean): List<Path> =
        Files.walk(root, maxDepth).use { stream ->
            stream
                .filter(Files::isRegularFile)
                .filter { !hasExcludedPathName(root, it) }
                .filter(predicate)
                .sorted()
                .collect(Collectors.toList())
        }

    private fun hasExcludedPathName(root: Path, path: Path): Boolean =
        root.relativize(path).any { it.toString() in excludedPathNames }
}
