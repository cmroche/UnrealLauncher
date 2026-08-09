package com.cmroche.unrealhelper.workflow

import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.config.resolveConfigurationEntries
import com.cmroche.unrealhelper.discovery.UnrealTargetType
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

data class UnrealWorkflowPreflightResult(
    val plan: UnrealExecutionPlan?,
    val errors: List<String>,
)

class UnrealWorkflowPreflightValidator(
    private val plan: (
        UnrealWorkflowRequest,
        TargetPlatformConfiguration,
        UnrealHelperSettingsState,
        String?,
    ) -> UnrealExecutionPlan = UnrealWorkflowPlanner()::plan,
    private val osName: String = System.getProperty("os.name"),
) {
    fun validate(
        request: UnrealWorkflowRequest,
        configuration: TargetPlatformConfiguration,
        state: UnrealHelperSettingsState,
        projectBasePath: String?,
    ): List<String> = prepare(request, configuration, state, projectBasePath).errors

    fun prepare(
        request: UnrealWorkflowRequest,
        configuration: TargetPlatformConfiguration,
        state: UnrealHelperSettingsState,
        projectBasePath: String?,
    ): UnrealWorkflowPreflightResult {
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

        val engineRoot = pathOrNull(state.engineRoot)
        when {
            state.engineRoot.isBlank() -> errors += "Engine root is not configured"
            engineRoot == null -> errors += "Engine root path is invalid: ${state.engineRoot}"
            !Files.isDirectory(engineRoot) -> errors += "Engine root was not found at $engineRoot"
        }

        val buildConfigurationValid = state.buildConfiguration in UnrealHelperSettings.BuildConfigurations
        if (!buildConfigurationValid) {
            errors += "Build configuration '${state.buildConfiguration}' is not supported"
        }
        val packageDestinationValid = request != UnrealWorkflowRequest.PACKAGE || pathOrNull(
            state.packageDirectory.ifBlank {
                workspaceRoot?.let(UnrealHelperSettings::defaultPackageDirectory).orEmpty()
            },
        ) != null
        if (!packageDestinationValid) {
            errors += "Package destination path is invalid"
        }

        if (!entryResolution.isValid || projectPath == null || workspaceRoot == null || engineRoot == null ||
            !buildConfigurationValid || !packageDestinationValid
        ) {
            return UnrealWorkflowPreflightResult(null, errors)
        }

        val executionPlan = try {
            plan(request, configuration, state, projectBasePath)
        } catch (exception: IllegalArgumentException) {
            errors += "Could not create workflow plan for configuration '${configuration.name}': ${exception.message}"
            return UnrealWorkflowPreflightResult(null, errors)
        } catch (exception: IllegalStateException) {
            errors += "Could not create workflow plan for configuration '${configuration.name}': ${exception.message}"
            return UnrealWorkflowPreflightResult(null, errors)
        }

        validatePlan(executionPlan, configuration.name, errors)
        executionPlan.phases
            .flatMap { it.actions }
            .flatMap { it.artifacts }
            .map { it.projectPath }
            .distinct()
            .filterNot(Files::isRegularFile)
            .forEach { errors.addUnique("Project file was not found at $it") }

        val environment = executionPlan.environment
        if (!Files.isDirectory(environment.workspaceRoot)) {
            errors.addUnique("Workspace root was not found at ${environment.workspaceRoot}")
        }
        if (!Files.isDirectory(environment.engineRoot)) {
            errors.addUnique("Engine root was not found at ${environment.engineRoot}")
        } else {
            val phases = executionPlan.phases.map { it.phase }.toSet()
            if (UnrealPhase.BUILD in phases) {
                val ubtPath = unrealBuildToolPath(environment.engineRoot)
                if (!Files.isRegularFile(ubtPath)) {
                    errors += "UnrealBuildTool was not found at $ubtPath"
                }
            }
            if (phases.any { it in UatPhases }) {
                val uatPath = runUatPath(environment.engineRoot)
                if (!Files.isRegularFile(uatPath)) {
                    errors += "RunUAT was not found at $uatPath"
                }
            }
        }
        if (executionPlan.phases.any { it.phase == UnrealPhase.PACKAGE } && errors.isEmpty()) {
            ensurePackageDestination(environment.packageDirectory, errors)
        }

        return UnrealWorkflowPreflightResult(executionPlan, errors)
    }

    private fun ensurePackageDestination(destination: Path, errors: MutableList<String>) {
        try {
            if (Files.exists(destination) && !Files.isDirectory(destination)) {
                errors += "Package destination is not a directory at $destination"
                return
            }
            val nearestExisting = generateSequence(destination.toAbsolutePath().normalize()) { it.parent }
                .firstOrNull(Files::exists)
            if (nearestExisting == null || !Files.isDirectory(nearestExisting) || !Files.isWritable(nearestExisting)) {
                errors += "Package destination is not writable at $destination"
                return
            }
            Files.createDirectories(destination)
            if (!Files.isWritable(destination)) errors += "Package destination is not writable at $destination"
        } catch (exception: RuntimeException) {
            errors += "Package destination could not be created at $destination: ${exception.message}"
        }
    }

    private fun validatePlan(
        executionPlan: UnrealExecutionPlan,
        configurationName: String,
        errors: MutableList<String>,
    ) {
        val phases = executionPlan.phases.map { it.phase }
        if (phases.isEmpty()) {
            errors += "Workflow plan for configuration '$configurationName' has no phases"
        } else if (phases.zipWithNext().any { (first, second) -> first.ordinal >= second.ordinal }) {
            errors += "Workflow plan for configuration '$configurationName' has invalid phase order: " +
                phases.joinToString(", ")
        }

        executionPlan.phases
            .filter { it.phase in UatPhases }
            .flatMap { phase -> phase.actions.flatMap { action -> action.artifacts } }
            .filter { it.targetType == UnrealTargetType.Editor.name }
            .distinct()
            .forEach { artifact ->
                errors += "Editor target '${artifact.targetName}' cannot be cooked, staged, or packaged; " +
                    "choose a Game, Client, or Server target"
            }
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

    private fun unrealBuildToolPath(engineRoot: Path): Path = engineRoot
        .resolve("Engine")
        .resolve("Binaries")
        .resolve("DotNET")
        .resolve("UnrealBuildTool")
        .resolve(if (isWindows()) "UnrealBuildTool.exe" else "UnrealBuildTool")

    private fun runUatPath(engineRoot: Path): Path = engineRoot
        .resolve("Engine")
        .resolve("Build")
        .resolve("BatchFiles")
        .resolve(if (isWindows()) "RunUAT.bat" else "RunUAT.sh")

    private fun isWindows(): Boolean = osName.startsWith("Windows", ignoreCase = true)

    private fun pathOrNull(value: String): Path? = try {
        value.takeIf(String::isNotBlank)?.let(Path::of)
    } catch (_: InvalidPathException) {
        null
    }

    private fun MutableList<String>.addUnique(message: String) {
        if (message !in this) add(message)
    }

    private companion object {
        val UatPhases = setOf(UnrealPhase.COOK, UnrealPhase.STAGE, UnrealPhase.PACKAGE)
    }
}
