package com.cmroche.unrealhelper.execution

import com.cmroche.unrealhelper.command.UnrealCommand
import com.cmroche.unrealhelper.command.UnrealCommandBuilder
import com.cmroche.unrealhelper.command.UnrealCommandContext
import com.cmroche.unrealhelper.launch.ResolvedLaunchArtifact
import com.cmroche.unrealhelper.launch.UnrealLaunchCommandBuilder
import com.cmroche.unrealhelper.launch.UnrealTargetReceiptResolver
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.cmroche.unrealhelper.workflow.BuildBatch
import com.cmroche.unrealhelper.workflow.Cook
import com.cmroche.unrealhelper.workflow.Launch
import com.cmroche.unrealhelper.workflow.Package
import com.cmroche.unrealhelper.workflow.Stage
import com.cmroche.unrealhelper.workflow.UnrealArtifactKey
import com.cmroche.unrealhelper.workflow.UnrealPlannedAction
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import java.nio.file.Path

internal interface UnrealPlannedActionProcessFactory {
    fun create(command: UnrealCommand): UnrealWorkflowProcess

    fun createLaunch(commandLine: GeneralCommandLine, title: String): UnrealWorkflowProcess
}

internal class RiderUnrealPlannedActionExecutor(
    private val settings: UnrealHelperSettings,
    private val processFactory: UnrealPlannedActionProcessFactory,
    private val receiptResolver: (UnrealArtifactKey, Path, Path) -> ResolvedLaunchArtifact =
        UnrealTargetReceiptResolver::resolve,
) : UnrealPlannedActionExecutor {
    constructor(project: Project, settings: UnrealHelperSettings) : this(
        settings = settings,
        processFactory = RiderUnrealPlannedActionProcessFactory(project),
    )

    override fun create(action: UnrealPlannedAction): UnrealWorkflowProcess = when (action) {
        is BuildBatch -> processFactory.create(
            UnrealCommandBuilder.buildBatch(action.artifacts.map(::context)),
        )
        is Cook -> processFactory.create(UnrealCommandBuilder.cook(context(action.artifact), action.mode))
        is Stage -> processFactory.create(UnrealCommandBuilder.stage(context(action.artifact)))
        is Package -> processFactory.create(UnrealCommandBuilder.packageProject(context(action.artifact)))
        is Launch -> createLaunch(action)
    }

    private fun createLaunch(action: Launch): UnrealWorkflowProcess {
        val state = settings.state
        val projectRoot = action.artifact.projectPath.parent
            ?: state.workspaceRoot.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: error("Project root is not configured")
        val engineRoot = state.engineRoot.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: error("Engine root is not configured")
        val artifact = receiptResolver(action.artifact, projectRoot, engineRoot)
        return processFactory.createLaunch(
            UnrealLaunchCommandBuilder.build(action, artifact),
            launchTitle(action),
        )
    }

    private fun context(artifact: UnrealArtifactKey): UnrealCommandContext {
        val state = settings.state
        val workspaceRoot = state.workspaceRoot.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: artifact.projectPath.parent
            ?: error("Workspace root is not configured")
        val engineRoot = state.engineRoot.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: error("Engine root is not configured")
        return UnrealCommandContext(
            artifact = artifact,
            engineRoot = engineRoot,
            workspaceRoot = workspaceRoot,
            packageDirectory = Path.of(settings.effectivePackageDirectory()),
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
