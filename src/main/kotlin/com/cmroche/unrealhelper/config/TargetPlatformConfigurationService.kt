package com.cmroche.unrealhelper.config

import com.cmroche.unrealhelper.launch.QuickLaunchProfileState
import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class TargetPlatformConfigurationService private constructor(
    private val settings: UnrealHelperSettings,
    private val store: TargetPlatformConfigurationStorage,
    private val ensureConfigurationFileWritable: (Path) -> Unit,
    private val snapshotChanged: () -> Unit,
) {
    @Volatile
    private var snapshot = ConfigurationSnapshot()

    private val snapshotLock = Any()
    private val storageLock = Any()
    private var requestedReloadVersion = 0L
    private val legacyMigrationAttempted = AtomicBoolean(false)
    private var reloadRequests: Channel<Unit>? = null

    constructor(project: Project, coroutineScope: CoroutineScope) : this(
        settings = project.service<UnrealHelperSettings>(),
        store = TargetPlatformConfigurationStore(),
        ensureConfigurationFileWritable = { path -> ensureConfigurationFileWritable(project, path) },
        snapshotChanged = { ActivityTracker.getInstance().inc() },
    ) {
        val requests = Channel<Unit>(Channel.CONFLATED)
        reloadRequests = requests
        project.messageBus.connect().subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val configurationPath = configurationPath() ?: return
                    if (events.any { event -> eventAffectsConfiguration(event, configurationPath) }) {
                        requestReload()
                    }
                }
            },
        )
        coroutineScope.launch(Dispatchers.IO) {
            for (request in requests) {
                delay(ReloadDebounceMillis)
                while (requests.tryReceive().isSuccess) {
                }
                reloadFromDisk()
            }
        }
        requests.trySend(Unit)
    }

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
        return snapshot.result ?: unloadedResult()
    }

    fun loadForManagement(): TargetPlatformConfigurationLoadResult {
        val currentSnapshot = snapshot
        if (currentSnapshot.dirty || currentSnapshot.result == null) {
            reloadFromDisk()
        }
        return load()
    }

    fun save(file: TargetPlatformConfigurationsFile) {
        val path = configurationPath() ?: error(MissingWorkspaceRootMessage)
        synchronized(storageLock) {
            legacyMigrationAttempted.set(true)
            ensureConfigurationFileWritable(path)
            publish(store.save(path, file))
        }
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
        reloadFromDisk()
    }

    fun clearStaleSelectionIfNeeded() {
        val selectedName = settings.state.selectedTargetPlatformConfigurationName.trim()
        if (selectedName.isEmpty()) {
            return
        }

        clearStaleSelection(load(), selectedName)
    }

    fun selectedConfigurationResult(): SelectedTargetPlatformConfigurationResult {
        val state = settings.state
        val currentSnapshot = snapshot
        if (currentSnapshot.dirty) {
            return SelectedTargetPlatformConfigurationResult.MalformedFile(
                configurationPath() ?: UnresolvedConfigurationPath,
                RefreshingConfigurationMessage,
            )
        }
        return resolveSelectedTargetPlatformConfiguration(
            loadResult = currentSnapshot.result ?: unloadedResult(),
            selectedName = state.selectedTargetPlatformConfigurationName,
            state = state,
        )
    }

    fun requestReload() {
        synchronized(snapshotLock) {
            requestedReloadVersion += 1
            snapshot = snapshot.copy(dirty = true)
        }
        reloadRequests?.trySend(Unit)
    }

    private fun reloadFromDisk() {
        synchronized(storageLock) {
            val reloadVersion = synchronized(snapshotLock) { requestedReloadVersion }
            val path = configurationPath()
            if (path == null) {
                publish(unloadedResult(), reloadVersion)
                return
            }

            val result = runCatching {
                val shouldMigrateIfMissing = legacyMigrationAttempted.compareAndSet(false, true)
                val loaded = store.load(path, settings.state.discoveredTargets)
                if (loaded is TargetPlatformConfigurationLoadResult.Missing &&
                    shouldMigrateIfMissing
                ) {
                    migrateLegacySelection(path) ?: loaded
                } else {
                    loaded
                }
            }.getOrElse { exception ->
                TargetPlatformConfigurationLoadResult.Malformed(
                    path,
                    exception.message ?: exception.javaClass.simpleName,
                )
            }
            publish(result, reloadVersion)
        }
    }

    private fun migrateLegacySelection(path: Path): TargetPlatformConfigurationLoadResult.Loaded? {
        val state = settings.state
        val targetTypes = normalizedValues(state.selectedTargetTypes)
        val platforms = normalizedValues(state.selectedPlatforms)
        if (targetTypes.isEmpty() || platforms.isEmpty()) {
            return null
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
        val saved = store.save(
            path,
            TargetPlatformConfigurationsFile(
                configurations = listOf(TargetPlatformConfiguration(DefaultName, entries)),
            ),
        )
        state.selectedTargetPlatformConfigurationName = DefaultName
        return saved
    }

    private fun publish(
        result: TargetPlatformConfigurationLoadResult,
        reloadVersion: Long? = null,
    ) {
        val remainsDirty = synchronized(snapshotLock) {
            val dirty = reloadVersion != null && reloadVersion != requestedReloadVersion
            snapshot = ConfigurationSnapshot(result = result, dirty = dirty)
            dirty
        }
        if (!remainsDirty) {
            clearStaleSelection(
                result,
                settings.state.selectedTargetPlatformConfigurationName.trim(),
            )
        }
        snapshotChanged()
    }

    private data class ConfigurationSnapshot(
        val result: TargetPlatformConfigurationLoadResult? = null,
        val dirty: Boolean = true,
    )

    private fun clearStaleSelection(
        result: TargetPlatformConfigurationLoadResult,
        selectedName: String,
    ) {
        if (selectedName.isNotEmpty() &&
            result is TargetPlatformConfigurationLoadResult.Loaded &&
            result.file.configurations.none { it.name == selectedName }
        ) {
            settings.state.selectedTargetPlatformConfigurationName = ""
        }
    }

    private fun unloadedResult(): TargetPlatformConfigurationLoadResult =
        configurationPath()?.let(TargetPlatformConfigurationLoadResult::Missing)
            ?: TargetPlatformConfigurationLoadResult.Malformed(
                UnresolvedConfigurationPath,
                MissingWorkspaceRootMessage,
            )

    companion object {
        private const val DefaultName = "Default"
        private const val MissingWorkspaceRootMessage = "Workspace root is not configured"
        private val UnresolvedConfigurationPath: Path = Path.of(".unrealhelper", "target-platforms.json")

        internal fun createForTest(
            settings: UnrealHelperSettings,
            store: TargetPlatformConfigurationStorage,
            ensureConfigurationFileWritable: (Path) -> Unit = {},
        ): TargetPlatformConfigurationService = TargetPlatformConfigurationService(
            settings,
            store,
            ensureConfigurationFileWritable,
        ) {}

        private fun ensureConfigurationFileWritable(project: Project, path: Path) {
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
            val status = ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(listOf(virtualFile))
            if (status.hasReadonlyFiles()) {
                throw IOException(status.readonlyFilesMessage)
            }
        }

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

        private fun eventAffectsConfiguration(event: VFileEvent, configurationPath: Path): Boolean =
            sequenceOf(event.path, event.file?.path)
                .filterNotNull()
                .any { eventPath -> pathAffectsConfiguration(eventPath, configurationPath) }

        internal fun pathAffectsConfiguration(eventPath: String, configurationPath: Path): Boolean {
            val normalizedEventPath = runCatching { Path.of(eventPath).toAbsolutePath().normalize() }.getOrNull()
                ?: return false
            val configurationDirectory = configurationPath.toAbsolutePath().normalize().parent ?: return false
            return normalizedEventPath == configurationDirectory || normalizedEventPath.startsWith(configurationDirectory)
        }

        private const val ReloadDebounceMillis = 100L
        private const val RefreshingConfigurationMessage = "Target & Platform configurations are refreshing"
    }
}
