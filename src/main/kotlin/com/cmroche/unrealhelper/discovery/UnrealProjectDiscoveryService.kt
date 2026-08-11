package com.cmroche.unrealhelper.discovery

import com.cmroche.unrealhelper.config.TargetPlatformConfigurationService
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.jetbrains.rd.framework.RdTaskResult
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rider.cpp.unreal.UnrealHost
import com.jetbrains.rider.projectView.SolutionConfigurationManager
import com.jetbrains.rider.projectView.solution
import com.jetbrains.rider.projectView.solutionFile
import com.jetbrains.rider.projectView.workspace.getId
import com.jetbrains.rider.projectView.workspace.getSolutionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class UnrealProjectDiscoveryService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {
    private val lifetimeDefinition = LifetimeDefinition()
    private val unrealHost = UnrealHost.getInstance(project)
    private val callbackLock = Any()
    private val completionCallbacks = mutableListOf<() -> Unit>()
    private var refreshInProgress = false
    private var refreshRequested = false

    init {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                unrealHost.model.isUProjectModel.advise(lifetimeDefinition.lifetime) { isUProjectModel ->
                    if (isUProjectModel) refresh()
                }
                unrealHost.model.isInitialBackgroundIndexingCompleted.advise(lifetimeDefinition.lifetime) { completed ->
                    if (completed) refresh()
                }
                project.solution.solutionProperties.configurationsAndPlatformsCollection
                    .advise(lifetimeDefinition.lifetime) { configurations ->
                        if (configurations.isNotEmpty()) refresh()
                    }
            }
        }
    }

    fun refresh(onComplete: (() -> Unit)? = null) {
        val shouldStart = synchronized(callbackLock) {
            onComplete?.let(completionCallbacks::add)
            if (refreshInProgress) {
                refreshRequested = true
                false
            } else {
                refreshInProgress = true
                true
            }
        }
        if (!shouldStart) return

        ApplicationManager.getApplication().invokeLater {
            startRefreshOnUiThread()
        }
    }

    private fun startRefreshOnUiThread() {
        if (project.isDisposed) {
            finishRefresh(null)
            return
        }
        if (unrealHost.model.isUProjectModel.valueOrNull != true) {
            finishRefresh(null)
            return
        }

        val projectModelId = runCatching {
            WorkspaceModel.getInstance(project).getSolutionEntity()?.getId(project)
        }.getOrNull()
        if (projectModelId == null) {
            LOG.debug("Rider solution entity is not available for Unreal target discovery")
            finishRefresh(null)
            return
        }

        runCatching {
            unrealHost.model.getAddModuleDialogData
                .start(lifetimeDefinition.lifetime, projectModelId)
                .result
                .advise(lifetimeDefinition.lifetime) { taskResult ->
                    when (taskResult) {
                        is RdTaskResult.Success -> handleRiderDiscovery(taskResult.value)
                        is RdTaskResult.Fault -> {
                            LOG.warn("Rider could not provide Unreal target information: ${taskResult.error}")
                            finishRefresh(null)
                        }
                        is RdTaskResult.Cancelled -> finishRefresh(null)
                    }
                }
        }.onFailure {
            LOG.warn("Rider Unreal target discovery failed", it)
            finishRefresh(null)
        }
    }

    private fun handleRiderDiscovery(sourceData: com.jetbrains.rd.ide.model.AddUnrealModuleDialogSourceData) {
        val input = discoveryInput(sourceData)
        if (input.targetFiles.isEmpty()) {
            LOG.debug("Rider Unreal target files are not populated yet")
            finishRefresh(null)
            return
        }

        scope.launch(Dispatchers.IO) {
            finishRefresh(
                UnrealProjectDiscovery.fromRiderModel(
                    uprojectPath = input.uprojectPath,
                    engineRoot = input.engineRoot,
                    targetFiles = input.targetFiles,
                    platforms = input.platforms,
                ),
            )
        }
    }

    private fun discoveryInput(sourceData: com.jetbrains.rd.ide.model.AddUnrealModuleDialogSourceData): RiderDiscoveryInput {
        val projectFile = project.solutionFile
            .toPath()
            .takeIf { it.fileName.toString().endsWith(".uproject", ignoreCase = true) }
        val engineRoot = unrealHost.model.unrealEngineLocation.valueOrNull
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
        val configurationsAndPlatforms = SolutionConfigurationManager.tryGetInstance(project)
            ?.solutionConfigurationsAndPlatforms
            .orEmpty()

        return RiderDiscoveryInput(
            uprojectPath = projectFile,
            engineRoot = engineRoot,
            targetFiles = sourceData.targets.map { target ->
                resolveRiderTargetPath(projectFile, target.path)
            },
            platforms = configurationsAndPlatforms.map { it.platform },
        )
    }

    private fun finishRefresh(result: UnrealProjectDiscoveryResult?) {
        ApplicationManager.getApplication().invokeLater {
            if (result != null && !project.isDisposed) {
                project.service<UnrealHelperSettings>().applyDiscoveryResult(result)
                project.service<TargetPlatformConfigurationService>().requestReload()
                LOG.info(
                    "Loaded ${result.targets.size} Unreal targets and " +
                        "${result.platforms.size} platforms from the Rider project model",
                )
            }
            val (restart, callbacks) = synchronized(callbackLock) {
                refreshInProgress = false
                if (result != null) refreshRequested = false
                if (refreshRequested) {
                    refreshRequested = false
                    refreshInProgress = true
                    true to emptyList()
                } else {
                    false to completionCallbacks.toList().also { completionCallbacks.clear() }
                }
            }
            if (restart) {
                startRefreshOnUiThread()
            } else {
                callbacks.forEach { it() }
            }
        }
    }

    override fun dispose() {
        lifetimeDefinition.terminate()
    }

    private companion object {
        val LOG: Logger = Logger.getInstance(UnrealProjectDiscoveryService::class.java)
    }

    private data class RiderDiscoveryInput(
        val uprojectPath: Path?,
        val engineRoot: Path?,
        val targetFiles: List<Path>,
        val platforms: List<String>,
    )
}

internal fun resolveRiderTargetPath(uprojectPath: Path?, riderPath: String): Path {
    val normalizedRiderPath = riderPath.trim()
    val logicalLocation = riderLogicalTargetPathRegex.matchEntire(normalizedRiderPath)
    if (logicalLocation != null && uprojectPath != null) {
        val fileName = Path.of(logicalLocation.groupValues[1]).fileName
        val relativeDirectory = logicalLocation.groupValues[2]
        return uprojectPath.parent
            .resolve(relativeDirectory)
            .resolve(fileName)
            .normalize()
    }

    val targetPath = Path.of(normalizedRiderPath)
    return if (targetPath.isAbsolute) {
        targetPath.normalize()
    } else {
        uprojectPath?.parent?.resolve(targetPath)?.normalize() ?: targetPath
    }
}

private val riderLogicalTargetPathRegex = Regex("""^(.+?\.Target\.cs).*<[^>]+>/?(.*)$""")
