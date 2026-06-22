package com.cmroche.unrealhelper.command

data class UnrealCommand(
    val title: String,
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String,
) {
    fun asList(): List<String> = listOf(executable) + arguments
}
