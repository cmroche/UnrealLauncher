package com.cmroche.unrealhelper.workflow

import java.nio.file.Path

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
) : UnrealPlannedAction {
    override val phase: UnrealPhase = UnrealPhase.COOK
    override val artifacts: Set<UnrealArtifactKey> = setOf(artifact)
}

data class Stage(
    val artifact: UnrealArtifactKey,
) : UnrealPlannedAction {
    override val phase: UnrealPhase = UnrealPhase.STAGE
    override val artifacts: Set<UnrealArtifactKey> = setOf(artifact)
}

data class Package(
    val artifact: UnrealArtifactKey,
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

data class UnrealExecutionPlan(
    val request: UnrealWorkflowRequest,
    val configurationName: String,
    val globalArguments: String,
    val phases: List<UnrealPlanPhase>,
)
