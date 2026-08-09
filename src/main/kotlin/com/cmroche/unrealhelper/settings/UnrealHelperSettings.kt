package com.cmroche.unrealhelper.settings

import com.cmroche.unrealhelper.discovery.DiscoveredUnrealTarget
import com.cmroche.unrealhelper.discovery.UnrealProjectDiscoveryResult
import com.cmroche.unrealhelper.discovery.UnrealTargetType
import com.cmroche.unrealhelper.launch.QuickLaunchProfileState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.nio.file.Path
import java.nio.file.Paths

class UnrealHelperSettingsState {
    var discoveryVersion: Int = 0
    var uprojectPath: String = ""
    var workspaceRoot: String = ""
    var packageDirectory: String = ""
    var engineRoot: String = ""
    var buildConfiguration: String = "Development"
    var selectedTargetTypes: MutableList<String> = mutableListOf(
        UnrealTargetType.Game.name,
        UnrealTargetType.Client.name,
        UnrealTargetType.Server.name,
    )
    var selectedPlatforms: MutableList<String> = mutableListOf()
    var selectedTargetPlatformConfigurationName: String = ""
    var discoveredTargets: MutableList<UnrealTargetState> = mutableListOf()
    var discoveredPlatforms: MutableList<String> = mutableListOf()
    var discoveryWarnings: MutableList<String> = mutableListOf()
    var activeCommandLine: String = ""
    var savedCommandLines: MutableList<String> = mutableListOf()
    var recentCommandLines: MutableList<String> = mutableListOf()
    var applyToRunDebug: Boolean = true
    var quickLaunchProfiles: MutableList<QuickLaunchProfileState> = mutableListOf()

    fun profileFor(targetType: String, platform: String): QuickLaunchProfileState =
        quickLaunchProfiles.firstOrNull { it.targetType == targetType && it.platform == platform }
            ?: QuickLaunchProfileState(
                name = "$targetType $platform",
                targetType = targetType,
                platform = platform,
            ).also { quickLaunchProfiles.add(it) }
}

class UnrealTargetState {
    var name: String = ""
    var type: String = UnrealTargetType.Game.name
    var path: String = ""
    var usesUniqueBuildEnvironment: Boolean = false
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

    fun applyDiscoveryResult(result: UnrealProjectDiscoveryResult) {
        state.discoveryVersion = CurrentDiscoveryVersion
        state.workspaceRoot = result.workspaceRoot.orEmpty()
        state.uprojectPath = result.uprojectPath.orEmpty()
        state.discoveredTargets = result.targets.map { it.toState() }.toMutableList()
        state.discoveredPlatforms = result.platforms.toMutableList()
        state.discoveryWarnings = result.warnings.toMutableList()

        if (state.packageDirectory.isBlank()) {
            state.packageDirectory = defaultPackageDirectory(state.workspaceRoot)
        }
        if (state.engineRoot.isBlank()) {
            state.engineRoot = result.engineRoot.orEmpty()
        }
        if (state.selectedPlatforms.isEmpty() && result.platforms.isNotEmpty()) {
            state.selectedPlatforms = result.platforms.toMutableList()
        }
    }

    fun effectivePackageDirectory(): String = state.packageDirectory.ifBlank {
        defaultPackageDirectory(state.workspaceRoot)
    }

    fun effectiveBuildConfiguration(): String =
        state.buildConfiguration.takeIf { it in BuildConfigurations } ?: DefaultBuildConfiguration

    fun hasConfiguredProject(): Boolean = state.uprojectPath.isNotBlank()

    companion object {
        const val CurrentDiscoveryVersion = 1
        const val DefaultBuildConfiguration = "Development"
        val BuildConfigurations: List<String> = listOf("Debug", "DebugGame", "Development", "Test", "Shipping")

        private const val MaxSavedCommandLines = 20
        private const val MaxRecentCommandLines = 10

        fun defaultPackageDirectory(workspaceRoot: String): String =
            if (workspaceRoot.isBlank()) {
                "Packages"
            } else {
                Paths.get(workspaceRoot).resolve("Packages").normalize().toString()
            }

        fun defaultPackageDirectory(workspaceRoot: Path): String =
            workspaceRoot.resolve("Packages").normalize().toString()

        private fun moveToFront(values: List<String>, value: String, maxSize: Int): List<String> =
            (listOf(value) + values.filterNot { it == value }).take(maxSize)
    }
}

private fun DiscoveredUnrealTarget.toState(): UnrealTargetState =
    UnrealTargetState().also {
        it.name = name
        it.type = type.name
        it.path = path
        it.usesUniqueBuildEnvironment = usesUniqueBuildEnvironment
    }
