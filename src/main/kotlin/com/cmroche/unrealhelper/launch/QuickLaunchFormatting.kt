package com.cmroche.unrealhelper.launch

import com.cmroche.unrealhelper.workflow.launchTitle

internal fun QuickLaunchKey.launchTitle(): String = launchTitle(
    configurationName = configurationName,
    rowIndex = entryIndex,
    targetName = targetName,
    targetType = targetType,
    platform = platform,
)
