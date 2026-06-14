package com.cmroche.unrealhelper.args

object CommandLineArguments {
    fun parse(commandLine: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var escaping = false

        for (char in commandLine) {
            when {
                escaping -> {
                    current.append(char)
                    escaping = false
                }
                char == '\\' -> {
                    current.append(char)
                    escaping = true
                }
                char == '\'' && !inDoubleQuote -> {
                    current.append(char)
                    inSingleQuote = !inSingleQuote
                }
                char == '"' && !inSingleQuote -> {
                    current.append(char)
                    inDoubleQuote = !inDoubleQuote
                }
                char.isWhitespace() && !inSingleQuote && !inDoubleQuote -> {
                    appendToken(result, current)
                }
                else -> current.append(char)
            }
        }

        appendToken(result, current)
        return result
    }

    fun render(arguments: Iterable<String>): String =
        arguments
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    fun toEditorText(commandLine: String): String = parse(commandLine).joinToString("\n")

    fun fromEditorText(editorText: String): String = render(editorText.lineSequence().toList())

    private fun appendToken(result: MutableList<String>, current: StringBuilder) {
        if (current.isNotEmpty()) {
            result += current.toString()
            current.clear()
        }
    }
}

