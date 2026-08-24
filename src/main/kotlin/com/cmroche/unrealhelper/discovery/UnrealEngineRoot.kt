package com.cmroche.unrealhelper.discovery

import java.nio.file.Files
import java.nio.file.Path

internal fun normalizeEngineRoot(path: Path): Path {
    val normalized = path.toAbsolutePath().normalize()
    if (looksLikeEngineDirectory(normalized)) return normalized

    val nestedEngine = normalized.resolve("Engine")
    return nestedEngine.takeIf(::looksLikeEngineDirectory) ?: normalized
}

private fun looksLikeEngineDirectory(path: Path): Boolean =
    Files.isDirectory(path.resolve("Build/BatchFiles")) ||
        Files.isDirectory(path.resolve("Binaries/DotNET/UnrealBuildTool"))
