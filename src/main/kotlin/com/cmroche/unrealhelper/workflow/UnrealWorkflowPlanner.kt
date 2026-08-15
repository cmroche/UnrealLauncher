package com.cmroche.unrealhelper.workflow

import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState

class UnrealWorkflowPlanner {
    fun plan(
        request: UnrealWorkflowRequest,
        configuration: TargetPlatformConfiguration,
        state: UnrealHelperSettingsState,
        projectBasePath: String?,
    ): UnrealExecutionPlan {
        require(state.buildConfiguration in UnrealHelperSettings.BuildConfigurations) {
            "Unsupported build configuration '${state.buildConfiguration}'"
        }
        val resolution = UnrealWorkflowInputResolver.resolve(request, configuration, state, projectBasePath)
        val inputs = requireNotNull(resolution.inputs) {
            "Cannot plan invalid configuration '${configuration.name}': ${resolution.errors.joinToString("; ")}"
        }
        return plan(inputs)
    }

    fun plan(inputs: ResolvedUnrealWorkflowInputs): UnrealExecutionPlan {
        val artifacts = inputs.entries
            .map { it.artifact }
            .distinct()
            .toCollection(linkedSetOf())

        val phases = when (inputs.request) {
            UnrealWorkflowRequest.BUILD -> buildList {
                addBuild(artifacts)
            }

            UnrealWorkflowRequest.COOK -> buildList {
                addPhase(UnrealPhase.COOK, plannedCooks(inputs.entries))
            }

            UnrealWorkflowRequest.PACKAGE -> buildList {
                addBuild(artifacts, includePackagingTools = true)
                addPhase(UnrealPhase.COOK, plannedCooks(inputs.entries))
                addPhase(UnrealPhase.STAGE, artifacts.map { Stage(it) })
                addPhase(UnrealPhase.PACKAGE, artifacts.map {
                    Package(
                        it,
                        archiveDirectory = inputs.environment.packageDirectory.resolve(artifactDirectoryName(it)),
                    )
                })
            }

            UnrealWorkflowRequest.LAUNCH,
            UnrealWorkflowRequest.DEBUG,
            -> buildList {
                addPhase(
                    UnrealPhase.LAUNCH,
                    inputs.entries.map { (entry, artifact) ->
                        Launch(
                            artifact = artifact,
                            configurationName = inputs.configurationName,
                            rowIndex = entry.index,
                            entryArguments = entry.arguments,
                            globalArguments = inputs.globalArguments,
                            cookedSandbox = artifactCookDirectory(artifact).takeIf { entry.cookOnLaunch },
                            mode = if (inputs.request == UnrealWorkflowRequest.DEBUG) {
                                UnrealLaunchMode.DEBUG
                            } else {
                                UnrealLaunchMode.RUN
                            },
                        )
                    },
                )
            }
        }

        return UnrealExecutionPlan(
            request = inputs.request,
            configurationName = inputs.configurationName,
            globalArguments = inputs.globalArguments,
            environment = inputs.environment,
            phases = phases,
        )
    }

    private fun MutableList<UnrealPlanPhase>.addBuild(
        artifacts: Set<UnrealArtifactKey>,
        includePackagingTools: Boolean = false,
    ) {
        if (artifacts.isNotEmpty()) {
            add(
                UnrealPlanPhase(
                    UnrealPhase.BUILD,
                    listOf(BuildBatch(artifacts, includePackagingTools)),
                ),
            )
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

    private fun plannedCooks(entries: List<ResolvedUnrealWorkflowEntry>): List<Cook> = deduplicateCooks(
        entries.map { (entry, artifact) ->
            Cook(
                artifact = artifact,
                mode = if (entry.incrementalCookOnLaunch) {
                    UnrealCookMode.INCREMENTAL
                } else {
                    UnrealCookMode.FULL
                },
            )
        },
    )

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
}
