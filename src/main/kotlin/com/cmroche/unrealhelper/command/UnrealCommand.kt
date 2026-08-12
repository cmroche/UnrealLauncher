package com.cmroche.unrealhelper.command

data class UnrealCommand(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String,
)
