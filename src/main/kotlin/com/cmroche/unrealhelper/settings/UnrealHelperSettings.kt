package com.cmroche.unrealhelper.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

class UnrealHelperSettingsState {
    var activeCommandLine: String = ""
    var savedCommandLines: MutableList<String> = mutableListOf()
    var recentCommandLines: MutableList<String> = mutableListOf()
    var applyToRunDebug: Boolean = true
}

@Service(Service.Level.PROJECT)
@State(name = "UnrealHelperSettings", storages = [Storage("unrealHelper.xml")])
class UnrealHelperSettings : PersistentStateComponent<UnrealHelperSettingsState> {
    private var state = UnrealHelperSettingsState()

    override fun getState(): UnrealHelperSettingsState = state

    override fun loadState(state: UnrealHelperSettingsState) {
        this.state = state
    }

    fun setActiveCommandLine(commandLine: String, rememberRecent: Boolean = true) {
        state.activeCommandLine = commandLine.trim()
        if (rememberRecent) {
            rememberCommandLine(state.activeCommandLine)
        }
    }

    fun saveCommandLine(commandLine: String) {
        val normalized = commandLine.trim()
        if (normalized.isEmpty()) return

        state.savedCommandLines = moveToFront(state.savedCommandLines, normalized, MaxSavedCommandLines).toMutableList()
        setActiveCommandLine(normalized)
    }

    fun rememberCommandLine(commandLine: String) {
        val normalized = commandLine.trim()
        if (normalized.isEmpty()) return

        state.recentCommandLines = moveToFront(state.recentCommandLines, normalized, MaxRecentCommandLines).toMutableList()
    }

    fun knownCommandLines(): List<String> =
        (state.savedCommandLines + state.recentCommandLines)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    companion object {
        private const val MaxSavedCommandLines = 20
        private const val MaxRecentCommandLines = 10

        private fun moveToFront(values: List<String>, value: String, maxSize: Int): List<String> =
            (listOf(value) + values.filterNot { it == value }).take(maxSize)
    }
}

