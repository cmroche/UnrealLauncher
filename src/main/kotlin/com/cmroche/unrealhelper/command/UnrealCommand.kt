package com.cmroche.unrealhelper.command

data class UnrealCommand(
    val title: String,
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String,
) {
    fun asList(): List<String> = listOf(executable) + arguments

    fun shellLine(osName: String = System.getProperty("os.name")): String =
        asList().joinToString(" ") {
            if (osName.startsWith("Windows", ignoreCase = true)) {
                it.quoteForWindowsShell()
            } else {
                it.quoteForShell()
            }
        }
}

internal fun String.quoteForShell(): String =
    if (isNotEmpty() && all { it.isLetterOrDigit() || it in "/._:-=" }) {
        this
    } else {
        "'${replace("'", "'\\''")}'"
    }

private fun String.quoteForWindowsShell(): String =
    if (isNotEmpty() && all { it.isLetterOrDigit() || it in "\\/:._-=" }) {
        this
    } else {
        "\"${replace("\"", "\\\"")}\""
    }
