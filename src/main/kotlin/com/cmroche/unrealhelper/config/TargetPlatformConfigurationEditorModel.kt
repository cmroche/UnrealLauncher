package com.cmroche.unrealhelper.config

class TargetPlatformConfigurationEditorModel(initialFile: TargetPlatformConfigurationsFile) {
    private val version = initialFile.version
    private val configurations = initialFile.normalized().configurations.toMutableList()

    var selectedName: String = configurations.firstOrNull()?.name.orEmpty()
        private set

    fun snapshot(): TargetPlatformConfigurationsFile =
        TargetPlatformConfigurationsFile(
            version = version,
            configurations = configurations.toList(),
        )

    fun select(name: String) {
        selectedName = if (configurations.any { it.name == name }) name else ""
    }

    fun addConfiguration(name: String): Boolean {
        val normalizedName = name.trim()
        if (normalizedName.isBlank() || configurations.any { it.name == normalizedName }) {
            return false
        }

        configurations.add(TargetPlatformConfiguration(normalizedName))
        selectedName = normalizedName
        return true
    }

    fun duplicateSelected(): Boolean {
        val index = selectedIndex()
        if (index == -1) {
            return false
        }

        val selected = configurations[index]
        val copyName = nextCopyName(selected.name)
        configurations.add(index + 1, selected.copy(name = copyName))
        selectedName = copyName
        return true
    }

    fun renameSelected(name: String): Boolean {
        val index = selectedIndex()
        val normalizedName = name.trim()
        if (index == -1 || normalizedName.isBlank()) {
            return false
        }
        if (configurations.withIndex().any { (existingIndex, configuration) ->
                existingIndex != index && configuration.name == normalizedName
            }
        ) {
            return false
        }

        configurations[index] = configurations[index].copy(name = normalizedName)
        selectedName = normalizedName
        return true
    }

    fun deleteSelected(): Boolean {
        val index = selectedIndex()
        if (index == -1) {
            return false
        }

        configurations.removeAt(index)
        selectedName = configurations.getOrNull(index)?.name
            ?: configurations.getOrNull(index - 1)?.name
            ?: ""
        return true
    }

    fun addEntry(entry: TargetPlatformEntry): Boolean {
        val index = selectedIndex()
        if (index == -1) {
            return false
        }

        val selected = configurations[index]
        configurations[index] = selected.copy(entries = selected.entries + entry.normalized())
        return true
    }

    fun setEntries(entries: List<TargetPlatformEntry>): Boolean {
        val index = selectedIndex()
        if (index == -1) {
            return false
        }

        configurations[index] = configurations[index].copy(entries = entries.map { it.normalized() })
        return true
    }

    private fun selectedIndex(): Int =
        configurations.indexOfFirst { it.name == selectedName }

    private fun nextCopyName(name: String): String {
        val existingNames = configurations.map { it.name }.toSet()
        val firstCopyName = "$name Copy"
        if (firstCopyName !in existingNames) {
            return firstCopyName
        }

        var suffix = 2
        while ("$firstCopyName $suffix" in existingNames) {
            suffix++
        }
        return "$firstCopyName $suffix"
    }
}
