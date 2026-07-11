package com.cmroche.unrealhelper.command

import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import java.nio.file.Path

data class UnrealCommandContext(
    val artifact: UnrealArtifactKey,
    val engineRoot: Path,
    val workspaceRoot: Path,
    val packageDirectory: Path,
    val extraArguments: String,
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

    constructor(
        uprojectPath: Path,
        engineRoot: Path,
        workspaceRoot: Path,
        packageDirectory: Path,
        buildConfiguration: String,
        targetName: String,
        targetType: String,
        platform: String,
        extraArguments: String,
    ) : this(
        artifact = UnrealArtifactKey(
            projectPath = uprojectPath,
            targetName = targetName,
            targetType = targetType,
            platform = platform,
            buildConfiguration = buildConfiguration,
        ),
        engineRoot = engineRoot,
        workspaceRoot = workspaceRoot,
        packageDirectory = packageDirectory,
        extraArguments = extraArguments,
    )
}
