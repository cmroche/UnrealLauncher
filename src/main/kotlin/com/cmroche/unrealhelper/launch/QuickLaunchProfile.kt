package com.cmroche.unrealhelper.launch

data class QuickLaunchProfileState(
    var name: String = "",
    var targetType: String = "Game",
    var platform: String = "Win64",
    var executablePath: String = "",
    var workingDirectory: String = "",
    var arguments: String = "",
)
