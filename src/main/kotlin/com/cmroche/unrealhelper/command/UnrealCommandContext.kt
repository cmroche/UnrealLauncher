package com.cmroche.unrealhelper.command

import java.nio.file.Path

data class UnrealCommandContext(
    val uprojectPath: Path,
    val engineRoot: Path,
    val workspaceRoot: Path,
    val packageDirectory: Path,
    val buildConfiguration: String,
    val targetName: String,
    val targetType: String,
    val platform: String,
    val extraArguments: String,
)
