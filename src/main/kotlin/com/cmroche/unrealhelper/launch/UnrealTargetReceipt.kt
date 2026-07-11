package com.cmroche.unrealhelper.launch

import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
internal data class UnrealTargetReceipt(
    @SerialName("TargetName") val targetName: String,
    @SerialName("Platform") val platform: String,
    @SerialName("Configuration") val configuration: String,
    @SerialName("TargetType") val targetType: String,
    @SerialName("Architecture") val architecture: String? = null,
    @SerialName("Project") val project: String? = null,
    @SerialName("Launch") val launch: String,
)

internal data class ResolvedLaunchArtifact(
    val receiptPath: Path,
    val executable: Path,
    val projectPath: Path?,
    val workingDirectory: Path,
    val engineRoot: Path,
)

internal object UnrealTargetReceiptResolver {
    private val json = Json { ignoreUnknownKeys = true }

    fun resolve(
        key: UnrealArtifactKey,
        projectRoot: Path,
        engineRoot: Path,
    ): ResolvedLaunchArtifact {
        val normalizedProjectRoot = projectRoot.toAbsolutePath().normalize()
        val normalizedEngineRoot = engineRoot.toAbsolutePath().normalize()
        val searchRoots = listOf(
            normalizedProjectRoot.resolve("Binaries").resolve(key.platform),
            normalizedEngineRoot.resolve("Engine/Binaries").resolve(key.platform),
        )
        val candidate = searchRoots
            .asSequence()
            .flatMap(::receiptCandidates)
            .mapNotNull { path -> readReceipt(path)?.let { path to it } }
            .firstOrNull { (_, receipt) ->
                receipt.targetName == key.targetName &&
                    receipt.platform == key.platform &&
                    receipt.configuration == key.buildConfiguration &&
                    (key.architecture == null || receipt.architecture == key.architecture)
            }
            ?: throw IllegalStateException(receiptNotFoundMessage(key, searchRoots))

        val (receiptPath, receipt) = candidate
        val projectPath = resolveProjectPath(receipt, receiptPath, key, normalizedProjectRoot, normalizedEngineRoot)
        val executable = resolveReceiptPath(
            value = receipt.launch,
            receiptPath = receiptPath,
            projectRoot = normalizedProjectRoot,
            engineRoot = normalizedEngineRoot,
        )

        return ResolvedLaunchArtifact(
            receiptPath = receiptPath,
            executable = executable,
            projectPath = projectPath,
            workingDirectory = checkNotNull(executable.parent) {
                "Receipt launch executable has no parent directory: $executable"
            },
            engineRoot = normalizedEngineRoot,
        )
    }

    private fun receiptCandidates(root: Path): Sequence<Path> {
        if (!Files.isDirectory(root)) return emptySequence()
        return Files.list(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".target") }
                .sorted()
                .toList()
                .asSequence()
        }
    }

    private fun readReceipt(path: Path): UnrealTargetReceipt? =
        runCatching { json.decodeFromString<UnrealTargetReceipt>(Files.readString(path)) }.getOrNull()

    private fun resolveProjectPath(
        receipt: UnrealTargetReceipt,
        receiptPath: Path,
        key: UnrealArtifactKey,
        projectRoot: Path,
        engineRoot: Path,
    ): Path? = receipt.project
        ?.takeIf { it.isNotBlank() }
        ?.let { resolveReceiptPath(it, receiptPath, projectRoot, engineRoot) }
        ?: key.projectPath
            .takeIf { it.toString().isNotBlank() }
            ?.let { if (it.isAbsolute) it else projectRoot.resolve(it) }
            ?.toAbsolutePath()
            ?.normalize()

    private fun resolveReceiptPath(
        value: String,
        receiptPath: Path,
        projectRoot: Path,
        engineRoot: Path,
    ): Path {
        val expanded = value
            .replace("\$(EngineDir)", engineRoot.resolve("Engine").toString())
            .replace("\$(ProjectDir)", projectRoot.toString())
        val path = Path.of(expanded)
        return (if (path.isAbsolute) path else receiptPath.parent.resolve(path))
            .toAbsolutePath()
            .normalize()
    }

    private fun receiptNotFoundMessage(key: UnrealArtifactKey, searchRoots: List<Path>): String =
        "Could not find target receipt for '${key.targetName}' " +
            "(${key.platform} ${key.buildConfiguration}" +
            (key.architecture?.let { " $it" } ?: "") +
            "). Searched: ${searchRoots.joinToString()}"
}
