package com.cmroche.unrealhelper.command

import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import java.nio.file.Path

data class UnrealCommandContext(
    val artifact: UnrealArtifactKey,
    val engineRoot: Path,
    val workspaceRoot: Path,
    val packageDirectory: Path,
) {
    val uprojectPath: Path
        get() = artifact.projectPath

    val buildConfiguration: String
        get() = artifact.buildConfiguration

    val targetName: String
        get() = artifact.targetName

    val targetType: String
        get() = artifact.targetType

    val platform: String
        get() = artifact.platform

}
