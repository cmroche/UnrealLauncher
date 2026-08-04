package com.cmroche.unrealhelper.workflow

import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class UnrealWorkflowRequest { BUILD, COOK, PACKAGE, LAUNCH }

enum class UnrealPhase { BUILD, COOK, STAGE, PACKAGE, LAUNCH }

enum class UnrealCookMode { FULL, INCREMENTAL }

data class UnrealArtifactKey(
    val projectPath: Path,
    val targetName: String,
    val targetType: String,
    val platform: String,
    val buildConfiguration: String,
    val architecture: String? = null,
)

sealed interface UnrealPlannedAction {
    val phase: UnrealPhase
    val artifacts: Set<UnrealArtifactKey>
}

data class BuildBatch(
    override val artifacts: Set<UnrealArtifactKey>,
) : UnrealPlannedAction {
    override val phase: UnrealPhase = UnrealPhase.BUILD
}

data class Cook(
    val artifact: UnrealArtifactKey,
    val mode: UnrealCookMode,
    val outputDirectory: Path = artifactCookDirectory(artifact),
) : UnrealPlannedAction {
    override val phase: UnrealPhase = UnrealPhase.COOK
    override val artifacts: Set<UnrealArtifactKey> = setOf(artifact)
}

data class Stage(
    val artifact: UnrealArtifactKey,
    val cookOutputDirectory: Path = artifactCookDirectory(artifact),
    val stagingDirectory: Path = artifactStageDirectory(artifact),
) : UnrealPlannedAction {
    override val phase: UnrealPhase = UnrealPhase.STAGE
    override val artifacts: Set<UnrealArtifactKey> = setOf(artifact)
}

data class Package(
    val artifact: UnrealArtifactKey,
    val cookOutputDirectory: Path = artifactCookDirectory(artifact),
    val stagingDirectory: Path = artifactStageDirectory(artifact),
    val archiveDirectory: Path? = null,
) : UnrealPlannedAction {
    override val phase: UnrealPhase = UnrealPhase.PACKAGE
    override val artifacts: Set<UnrealArtifactKey> = setOf(artifact)
}

data class Launch(
    val artifact: UnrealArtifactKey,
    val configurationName: String,
    val rowIndex: Int,
    val entryArguments: String,
    val globalArguments: String,
    val cookedSandbox: Path? = null,
) : UnrealPlannedAction {
    override val phase: UnrealPhase = UnrealPhase.LAUNCH
    override val artifacts: Set<UnrealArtifactKey> = setOf(artifact)
}

data class UnrealPlanPhase(
    val phase: UnrealPhase,
    val actions: List<UnrealPlannedAction>,
) {
    init {
        require(actions.isNotEmpty()) { "A planned phase must contain at least one action" }
        require(actions.all { it.phase == phase }) { "Every action must belong to the planned phase" }
    }
}

data class UnrealExecutionEnvironment(
    val engineRoot: Path,
    val workspaceRoot: Path,
    val packageDirectory: Path,
)

data class UnrealExecutionPlan(
    val request: UnrealWorkflowRequest,
    val configurationName: String,
    val globalArguments: String,
    val environment: UnrealExecutionEnvironment,
    val phases: List<UnrealPlanPhase>,
)

internal fun artifactCookDirectory(artifact: UnrealArtifactKey): Path = artifact.projectPath.parent
    .resolve("Saved/UnrealHelper/Cooked")
    .resolve(artifactDirectoryName(artifact))
    .resolve(cookPlatformName(artifact))

internal fun artifactStageDirectory(artifact: UnrealArtifactKey): Path = artifact.projectPath.parent
    .resolve("Saved/UnrealHelper/Staged")
    .resolve(artifactDirectoryName(artifact))

internal fun artifactDirectoryName(artifact: UnrealArtifactKey): String = listOfNotNull(
    artifact.targetName,
    artifact.targetType,
    artifact.platform,
    artifact.buildConfiguration,
    artifact.architecture,
).joinToString("-") { value -> value.replace(Regex("[^A-Za-z0-9_.-]"), "_") } +
    "-${artifactIdentityHash(artifact)}"

private fun artifactIdentityHash(artifact: UnrealArtifactKey): String {
    val identity = listOf(
        artifact.projectPath.toAbsolutePath().normalize().toString(),
        artifact.targetName,
        artifact.targetType,
        artifact.platform,
        artifact.buildConfiguration,
        artifact.architecture.orEmpty(),
    ).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(StandardCharsets.UTF_8))
        .take(6)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal fun cookPlatformName(artifact: UnrealArtifactKey): String {
    val base = when (artifact.platform.lowercase()) {
        "win64", "windows" -> "Windows"
        "mac" -> "Mac"
        else -> artifact.platform
    }
    return when (artifact.targetType.lowercase()) {
        "client" -> "${base}Client"
        "server" -> "${base}Server"
        else -> base
    }
}
