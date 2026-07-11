package com.cmroche.unrealhelper.config

import com.cmroche.unrealhelper.launch.QuickLaunchProfileState
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
class TargetPlatformConfigurationService private constructor(
    private val settings: UnrealHelperSettings,
    private val store: TargetPlatformConfigurationStore,
) {
    constructor(project: Project) : this(project.service<UnrealHelperSettings>(), TargetPlatformConfigurationStore())

    fun configurationPath(): Path? {
        val state = settings.state
        val workspaceRoot = state.workspaceRoot.trim()
        if (workspaceRoot.isNotEmpty()) {
            return sharedConfigurationPath(Paths.get(workspaceRoot))
        }

        val uprojectPath = state.uprojectPath.trim()
        if (uprojectPath.isNotEmpty()) {
            val projectRoot = Paths.get(uprojectPath).parent
            if (projectRoot != null) {
                return sharedConfigurationPath(projectRoot)
            }
        }

        return null
    }

    fun load(): TargetPlatformConfigurationLoadResult {
        val path = configurationPath()
            ?: return TargetPlatformConfigurationLoadResult.Malformed(UnresolvedConfigurationPath, MissingWorkspaceRootMessage)
        return store.load(path, settings.state.discoveredTargets)
    }

    fun loadForManagement(): TargetPlatformConfigurationLoadResult {
        migrateLegacySelectionIfNeeded()
        return load()
    }

    fun save(file: TargetPlatformConfigurationsFile) {
        val path = configurationPath() ?: error(MissingWorkspaceRootMessage)
        store.save(path, file)
    }

    fun saveManagedConfigurations(
        file: TargetPlatformConfigurationsFile,
        dialogSelectedName: String,
    ) {
        val previousSelectedName = settings.state.selectedTargetPlatformConfigurationName
        val normalizedFile = file.normalized()
        save(normalizedFile)
        settings.state.selectedTargetPlatformConfigurationName = selectedNameAfterManagedSave(
            previousSelectedName = previousSelectedName,
            file = normalizedFile,
            dialogSelectedName = dialogSelectedName,
        )
    }

    fun migrateLegacySelectionIfNeeded() {
        val path = configurationPath() ?: return
        if (store.load(path, settings.state.discoveredTargets) !is TargetPlatformConfigurationLoadResult.Missing) {
            return
        }

        val state = settings.state
        val targetTypes = normalizedValues(state.selectedTargetTypes)
        val platforms = normalizedValues(state.selectedPlatforms)
        if (targetTypes.isEmpty() || platforms.isEmpty()) {
            return
        }

        val entries = targetTypes.flatMap { targetType ->
            platforms.map { platform ->
                val profile = matchingProfile(state.quickLaunchProfiles, targetType, platform)
                TargetPlatformEntry(
                    targetName = migratedTargetName(targetType, state.discoveredTargets),
                    platform = platform,
                    arguments = profile?.arguments.orEmpty(),
                )
            }
        }
        store.save(
            path,
            TargetPlatformConfigurationsFile(
                configurations = listOf(TargetPlatformConfiguration(DefaultName, entries)),
            ),
        )
        state.selectedTargetPlatformConfigurationName = DefaultName
    }

    fun clearStaleSelectionIfNeeded() {
        val selectedName = settings.state.selectedTargetPlatformConfigurationName.trim()
        if (selectedName.isEmpty()) {
            return
        }

        val result = load()
        if (result is TargetPlatformConfigurationLoadResult.Loaded &&
            result.file.configurations.none { it.name == selectedName }
        ) {
            settings.state.selectedTargetPlatformConfigurationName = ""
        }
    }

    fun selectedConfigurationResult(): SelectedTargetPlatformConfigurationResult {
        migrateLegacySelectionIfNeeded()
        clearStaleSelectionIfNeeded()
        val state = settings.state
        return resolveSelectedTargetPlatformConfiguration(
            loadResult = load(),
            selectedName = state.selectedTargetPlatformConfigurationName,
            state = state,
        )
    }

    companion object {
        private const val DefaultName = "Default"
        private const val MissingWorkspaceRootMessage = "Workspace root is not configured"
        private val UnresolvedConfigurationPath: Path = Path.of(".unrealhelper", "target-platforms.json")

        fun createForTest(
            settings: UnrealHelperSettings,
            store: TargetPlatformConfigurationStore,
        ): TargetPlatformConfigurationService = TargetPlatformConfigurationService(settings, store)

        private fun sharedConfigurationPath(root: Path): Path =
            root.resolve(".unrealhelper").resolve("target-platforms.json").normalize()

        private fun normalizedValues(values: List<String>): List<String> =
            values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        private fun matchingProfile(
            profiles: List<QuickLaunchProfileState>,
            targetType: String,
            platform: String,
        ): QuickLaunchProfileState? =
            profiles.firstOrNull {
                it.targetType.trim() == targetType && it.platform.trim() == platform
            }

        private fun selectedNameAfterManagedSave(
            previousSelectedName: String,
            file: TargetPlatformConfigurationsFile,
            dialogSelectedName: String,
        ): String {
            val previousName = previousSelectedName.trim()
            if (previousName.isEmpty()) {
                return ""
            }

            val configurationNames = file.configurations.map { it.name }.toSet()
            if (previousName in configurationNames) {
                return previousName
            }

            val selectedName = dialogSelectedName.trim()
            return selectedName.takeIf { it in configurationNames }.orEmpty()
        }
    }
}
