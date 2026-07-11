package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.command.UnrealCommand
import com.cmroche.unrealhelper.command.UnrealCommandBuilder
import com.cmroche.unrealhelper.command.UnrealCommandContext
import com.cmroche.unrealhelper.launch.ResolvedLaunchArtifact
import com.cmroche.unrealhelper.launch.UnrealLaunchCommandBuilder
import com.cmroche.unrealhelper.launch.UnrealTargetReceiptResolver
import com.cmroche.unrealhelper.workflow.BuildBatch
import com.cmroche.unrealhelper.workflow.Cook
import com.cmroche.unrealhelper.workflow.Launch
import com.cmroche.unrealhelper.workflow.Package
import com.cmroche.unrealhelper.workflow.Stage
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import com.cmroche.unrealhelper.workflow.UnrealExecutionEnvironment
import com.cmroche.unrealhelper.workflow.UnrealPlannedAction
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import java.nio.file.Path

internal interface UnrealPlannedActionProcessFactory {
    fun create(command: UnrealCommand): UnrealWorkflowProcess

    fun createLaunch(commandLine: GeneralCommandLine, title: String): UnrealWorkflowProcess
}

internal class RiderUnrealPlannedActionExecutor(
    private val processFactory: UnrealPlannedActionProcessFactory,
    private val receiptResolver: (UnrealArtifactKey, Path, Path) -> ResolvedLaunchArtifact =
        UnrealTargetReceiptResolver::resolve,
) : UnrealPlannedActionExecutor {
    constructor(project: Project) : this(
        processFactory = RiderUnrealPlannedActionProcessFactory(project),
    )

    override fun create(
        action: UnrealPlannedAction,
        environment: UnrealExecutionEnvironment,
    ): UnrealWorkflowProcess = when (action) {
        is BuildBatch -> processFactory.create(
            UnrealCommandBuilder.buildBatch(action.artifacts.map { context(it, environment) }),
        )
        is Cook -> processFactory.create(UnrealCommandBuilder.cook(context(action.artifact, environment), action.mode))
        is Stage -> processFactory.create(UnrealCommandBuilder.stage(context(action.artifact, environment)))
        is Package -> processFactory.create(UnrealCommandBuilder.packageProject(context(action.artifact, environment)))
        is Launch -> createLaunch(action, environment)
    }

    private fun createLaunch(action: Launch, environment: UnrealExecutionEnvironment): UnrealWorkflowProcess {
        val projectRoot = action.artifact.projectPath.parent
            ?: environment.workspaceRoot
        val artifact = receiptResolver(action.artifact, projectRoot, environment.engineRoot)
        return processFactory.createLaunch(
            UnrealLaunchCommandBuilder.build(action, artifact),
            launchTitle(action),
        )
    }

    private fun context(
        artifact: UnrealArtifactKey,
        environment: UnrealExecutionEnvironment,
    ): UnrealCommandContext {
        return UnrealCommandContext(
            artifact = artifact,
            engineRoot = environment.engineRoot,
            workspaceRoot = environment.workspaceRoot,
            packageDirectory = environment.packageDirectory,
        )
    }

    private fun launchTitle(action: Launch): String =
        "Unreal ${action.configurationName} ${action.rowIndex + 1}: " +
            "${action.artifact.targetName} ${action.artifact.targetType} ${action.artifact.platform}"
}

private class RiderUnrealPlannedActionProcessFactory(
    private val project: Project,
) : UnrealPlannedActionProcessFactory {
    override fun create(command: UnrealCommand): UnrealWorkflowProcess =
        UnrealWorkflowProcessFactory.create(command)

    override fun createLaunch(commandLine: GeneralCommandLine, title: String): UnrealWorkflowProcess =
        UnrealWorkflowProcessFactory.createLaunch(project, commandLine, title)
}
