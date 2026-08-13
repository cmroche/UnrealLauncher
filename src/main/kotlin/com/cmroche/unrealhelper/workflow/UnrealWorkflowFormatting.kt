package com.cmroche.unrealhelper.workflow

internal fun UnrealArtifactKey.descriptor(): String = buildString {
    append(targetName)
    append(" [")
    append(targetType)
    append(", ")
    append(platform)
    append(", ")
    append(buildConfiguration)
    architecture?.let {
        append(", ")
        append(it)
    }
    append(']')
}

internal fun UnrealPlannedAction.displayName(): String =
    when (this) {
        is BuildBatch -> "Build ${artifacts.joinToString(separator = "; ") { it.descriptor() }}"
        is Cook -> "Cook ${artifact.descriptor()} (${mode.name.lowercase()})"
        is Stage -> "Stage ${artifact.descriptor()}"
        is Package -> "Package ${artifact.descriptor()}"
        is Launch -> "Launch ${artifact.descriptor()} ($configurationName)"
    }

internal fun Launch.launchTitle(): String = launchTitle(
    configurationName = configurationName,
    rowIndex = rowIndex,
    targetName = artifact.targetName,
    targetType = artifact.targetType,
    platform = artifact.platform,
)

internal fun launchTitle(
    configurationName: String,
    rowIndex: Int,
    targetName: String,
    targetType: String,
    platform: String,
): String = "Unreal $configurationName ${rowIndex + 1}: $targetName $targetType $platform"
