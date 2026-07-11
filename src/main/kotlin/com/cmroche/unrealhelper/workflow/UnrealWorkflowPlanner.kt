package com.cmroche.unrealhelper.workflow

import com.cmroche.unrealhelper.config.ResolvedTargetPlatformEntry
import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.config.resolveConfigurationEntries
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import java.nio.file.Path

class UnrealWorkflowPlanner {
    fun plan(
        request: UnrealWorkflowRequest,
        configuration: TargetPlatformConfiguration,
        state: UnrealHelperSettingsState,
        projectBasePath: String?,
    ): UnrealExecutionPlan {
        val resolution = resolveConfigurationEntries(configuration, state)
        require(resolution.isValid) {
            "Cannot plan invalid configuration '${configuration.name}': ${resolution.messages.joinToString("; ")}"
        }

        val projectPath = resolveProjectPath(state.uprojectPath, projectBasePath)
        val buildConfiguration = effectiveBuildConfiguration(state)
        val entriesWithArtifacts = resolution.entries.map { entry ->
            entry to entry.toArtifact(projectPath, buildConfiguration)
        }
        val artifacts = entriesWithArtifacts
            .map { it.second }
            .distinct()
            .toCollection(linkedSetOf())
        val globalArguments = state.activeCommandLine

        val phases = when (request) {
            UnrealWorkflowRequest.BUILD -> buildList {
                addBuild(artifacts)
            }

            UnrealWorkflowRequest.COOK -> buildList {
                addPhase(UnrealPhase.COOK, fullCooks(artifacts))
            }

            UnrealWorkflowRequest.PACKAGE -> buildList {
                addBuild(artifacts)
                addPhase(UnrealPhase.COOK, fullCooks(artifacts))
                addPhase(UnrealPhase.STAGE, artifacts.map { Stage(it) })
                val packageRoot = Path.of(state.packageDirectory.ifBlank {
                    UnrealHelperSettings.defaultPackageDirectory(workspaceRoot(state, projectPath))
                })
                addPhase(UnrealPhase.PACKAGE, artifacts.map {
                    Package(it, archiveDirectory = packageRoot.resolve(artifactDirectoryName(it)))
                })
            }

            UnrealWorkflowRequest.LAUNCH -> buildList {
                addBuild(artifacts)
                addPhase(
                    UnrealPhase.COOK,
                    deduplicateCooks(
                        entriesWithArtifacts.mapNotNull { (entry, artifact) ->
                            entry.takeIf { it.cookOnLaunch }?.let {
                                Cook(
                                    artifact = artifact,
                                    mode = if (it.incrementalCookOnLaunch) {
                                        UnrealCookMode.INCREMENTAL
                                    } else {
                                        UnrealCookMode.FULL
                                    },
                                )
                            }
                        },
                    ),
                )
                addPhase(
                    UnrealPhase.LAUNCH,
                    entriesWithArtifacts.map { (entry, artifact) ->
                        Launch(
                            artifact = artifact,
                            configurationName = configuration.name,
                            rowIndex = entry.index,
                            entryArguments = entry.arguments,
                            globalArguments = globalArguments,
                            cookedSandbox = artifactCookDirectory(artifact).takeIf { entry.cookOnLaunch },
                        )
                    },
                )
            }
        }

        val workspaceRoot = workspaceRoot(state, projectPath)
        return UnrealExecutionPlan(
            request = request,
            configurationName = configuration.name,
            globalArguments = globalArguments,
            environment = UnrealExecutionEnvironment(
                engineRoot = Path.of(state.engineRoot),
                workspaceRoot = workspaceRoot,
                packageDirectory = if (request == UnrealWorkflowRequest.PACKAGE) {
                    Path.of(
                        state.packageDirectory.ifBlank {
                            UnrealHelperSettings.defaultPackageDirectory(workspaceRoot)
                        },
                    )
                } else {
                    workspaceRoot.resolve("Packages")
                },
            ),
            phases = phases,
        )
    }

    private fun workspaceRoot(state: UnrealHelperSettingsState, projectPath: Path): Path =
        state.workspaceRoot.takeIf(String::isNotBlank)?.let(Path::of)
            ?: projectPath.parent
            ?: error("Workspace root is not configured")

    private fun MutableList<UnrealPlanPhase>.addBuild(artifacts: Set<UnrealArtifactKey>) {
        if (artifacts.isNotEmpty()) {
            add(UnrealPlanPhase(UnrealPhase.BUILD, listOf(BuildBatch(artifacts))))
        }
    }

    private fun MutableList<UnrealPlanPhase>.addPhase(
        phase: UnrealPhase,
        actions: List<UnrealPlannedAction>,
    ) {
        if (actions.isNotEmpty()) {
            add(UnrealPlanPhase(phase, actions))
        }
    }

    private fun fullCooks(artifacts: Set<UnrealArtifactKey>): List<Cook> =
        artifacts.map { Cook(it, UnrealCookMode.FULL) }

    private fun deduplicateCooks(candidates: List<Cook>): List<Cook> =
        candidates.groupBy { it.artifact }.map { (_, cooks) ->
            cooks.first().copy(
                mode = if (cooks.any { it.mode == UnrealCookMode.FULL }) {
                    UnrealCookMode.FULL
                } else {
                    UnrealCookMode.INCREMENTAL
                },
            )
        }

    private fun ResolvedTargetPlatformEntry.toArtifact(
        projectPath: Path,
        buildConfiguration: String,
    ): UnrealArtifactKey = UnrealArtifactKey(
        projectPath = projectPath,
        targetName = targetName,
        targetType = targetType,
        platform = platform,
        buildConfiguration = buildConfiguration,
    )

    private fun resolveProjectPath(uprojectPath: String, projectBasePath: String?): Path {
        val path = Path.of(uprojectPath)
        return when {
            path.isAbsolute -> path.normalize()
            !projectBasePath.isNullOrBlank() -> Path.of(projectBasePath).resolve(path).normalize()
            else -> path.normalize()
        }
    }

    private fun effectiveBuildConfiguration(state: UnrealHelperSettingsState): String {
        require(state.buildConfiguration in UnrealHelperSettings.BuildConfigurations) {
            "Unsupported build configuration '${state.buildConfiguration}'"
        }
        return state.buildConfiguration
    }
}
