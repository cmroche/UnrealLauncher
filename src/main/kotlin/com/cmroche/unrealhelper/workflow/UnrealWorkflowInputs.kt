package com.cmroche.unrealhelper.workflow

import com.cmroche.unrealhelper.config.ResolvedTargetPlatformEntry
import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.config.resolveConfigurationEntries
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

data class ResolvedUnrealWorkflowEntry(
    val entry: ResolvedTargetPlatformEntry,
    val artifact: UnrealArtifactKey,
)

data class ResolvedUnrealWorkflowInputs(
    val request: UnrealWorkflowRequest,
    val configurationName: String,
    val globalArguments: String,
    val environment: UnrealExecutionEnvironment,
    val entries: List<ResolvedUnrealWorkflowEntry>,
)

internal data class UnrealWorkflowInputResolution(
    val inputs: ResolvedUnrealWorkflowInputs?,
    val errors: List<String>,
    val isReady: Boolean,
)

internal object UnrealWorkflowInputResolver {
    fun resolve(
        request: UnrealWorkflowRequest,
        configuration: TargetPlatformConfiguration,
        state: UnrealHelperSettingsState,
        projectBasePath: String?,
    ): UnrealWorkflowInputResolution {
        val errors = mutableListOf<String>()
        val entryResolution = resolveConfigurationEntries(configuration, state)
        errors += entryResolution.messages.map {
            "Target & Platform configuration '${configuration.name}': $it"
        }

        val projectPath = resolveProjectPath(state.uprojectPath, projectBasePath)
        when {
            state.uprojectPath.isBlank() -> errors += "Project file is not configured"
            projectPath == null -> errors += "Project file path is invalid: ${state.uprojectPath}"
            !Files.isRegularFile(projectPath) -> errors += "Project file was not found at $projectPath"
        }

        val workspaceRoot = resolveWorkspaceRoot(state, projectPath, projectBasePath)
        when {
            workspaceRoot == null -> errors += "Workspace root is not configured"
            !Files.isDirectory(workspaceRoot) -> errors += "Workspace root was not found at $workspaceRoot"
        }

        val engineRoot = pathOrNull(state.engineRoot, allowBlank = true)
        when {
            state.engineRoot.isBlank() -> errors += "Engine root is not configured"
            engineRoot == null -> errors += "Engine root path is invalid: ${state.engineRoot}"
            !Files.isDirectory(engineRoot) -> errors += "Engine root was not found at $engineRoot"
        }

        val buildConfiguration = state.buildConfiguration.takeIf {
            it in UnrealHelperSettings.BuildConfigurations
        }
        if (buildConfiguration == null) {
            errors += "Build configuration '${state.buildConfiguration}' is not supported"
        }

        val packageDirectory = workspaceRoot?.let { root ->
            if (request == UnrealWorkflowRequest.PACKAGE) {
                pathOrNull(
                    state.packageDirectory.ifBlank {
                        UnrealHelperSettings.defaultPackageDirectory(root)
                    },
                )
            } else {
                root.resolve("Packages")
            }
        }
        if (request == UnrealWorkflowRequest.PACKAGE && packageDirectory == null) {
            errors += "Package destination path is invalid"
        }

        val isReady = entryResolution.isValid && projectPath != null && workspaceRoot != null &&
            state.engineRoot.isNotBlank() && engineRoot != null && buildConfiguration != null && packageDirectory != null
        val inputs = if (
            entryResolution.isValid && projectPath != null && workspaceRoot != null && engineRoot != null &&
            buildConfiguration != null && packageDirectory != null
        ) {
            ResolvedUnrealWorkflowInputs(
                request = request,
                configurationName = configuration.name,
                globalArguments = state.activeCommandLine,
                environment = UnrealExecutionEnvironment(
                    engineRoot = engineRoot,
                    workspaceRoot = workspaceRoot,
                    packageDirectory = packageDirectory,
                ),
                entries = entryResolution.entries.map { entry ->
                    ResolvedUnrealWorkflowEntry(
                        entry = entry,
                        artifact = UnrealArtifactKey(
                            projectPath = projectPath,
                            targetName = entry.targetName,
                            targetType = entry.targetType,
                            platform = entry.platform,
                            buildConfiguration = buildConfiguration,
                        ),
                    )
                },
            )
        } else {
            null
        }

        return UnrealWorkflowInputResolution(inputs, errors, isReady)
    }

    private fun resolveProjectPath(uprojectPath: String, projectBasePath: String?): Path? {
        val path = pathOrNull(uprojectPath) ?: return null
        return when {
            path.isAbsolute -> path.normalize()
            !projectBasePath.isNullOrBlank() -> pathOrNull(projectBasePath)?.resolve(path)?.normalize()
            else -> path.normalize()
        }
    }

    private fun resolveWorkspaceRoot(
        state: UnrealHelperSettingsState,
        projectPath: Path?,
        projectBasePath: String?,
    ): Path? = when {
        state.workspaceRoot.isNotBlank() -> pathOrNull(state.workspaceRoot)
        projectPath?.parent != null -> projectPath.parent
        !projectBasePath.isNullOrBlank() -> pathOrNull(projectBasePath)
        else -> null
    }

    private fun pathOrNull(value: String, allowBlank: Boolean = false): Path? = try {
        value.takeIf { allowBlank || it.isNotBlank() }?.let(Path::of)
    } catch (_: InvalidPathException) {
        null
    }
}
