package com.cmroche.unrealhelper.actions

import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.config.TargetPlatformEntry
import com.cmroche.unrealhelper.launch.QuickLaunchKey
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.cmroche.unrealhelper.settings.UnrealTargetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class UnrealQuickLaunchActionsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `launch commands preserve duplicate selected configuration entries`() {
        val state = settingsState()
        state.activeCommandLine = "-global"
        val executable = regularFile("Windows/MyGame.exe")
        val workingDirectory = Files.createDirectory(packageDirectory().resolve("Working"))
        val configuration = TargetPlatformConfiguration(
            name = "Three Clients",
            entries = listOf(
                TargetPlatformEntry(
                    targetType = "Game",
                    platform = "Win64",
                    arguments = "-first",
                    workingDirectory = workingDirectory.toString(),
                ),
                TargetPlatformEntry(
                    targetType = "Game",
                    platform = "Win64",
                    arguments = "-second",
                    executablePath = executable.toString(),
                ),
            ),
        )

        val commands = createQuickLaunchCommands(
            state = state,
            configuration = configuration,
            packageDirectory = packageDirectory(),
            resolveExecutable = { _, _, _ -> executable },
        )

        assertEquals(
            listOf(
                QuickLaunchKey("Three Clients", 0, "Game", "Win64"),
                QuickLaunchKey("Three Clients", 1, "Game", "Win64"),
            ),
            commands.map { it.key },
        )
        assertEquals(
            listOf(
                listOf("-first", "-global"),
                listOf("-second", "-global"),
            ),
            commands.map { it.commandLine.parametersList.list },
        )
        assertEquals(workingDirectory, commands.first().commandLine.workingDirectory)
        assertEquals(executable.parent, commands[1].commandLine.workingDirectory)
    }

    @Test
    fun `unresolved executable in selected configuration prevents partial launch commands`() {
        val state = settingsState()
        val executable = regularFile("Windows/MyGame.exe")
        val configuration = TargetPlatformConfiguration(
            name = "Three Clients",
            entries = listOf(
                TargetPlatformEntry(targetType = "Game", platform = "Win64", arguments = "-first"),
                TargetPlatformEntry(targetType = "Game", platform = "Win64", arguments = "-second"),
            ),
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            createQuickLaunchCommands(
                state = state,
                configuration = configuration,
                packageDirectory = packageDirectory(),
                resolveExecutable = { profile, _, _ ->
                    if (profile.arguments == "-first") executable else null
                },
            )
        }

        assertEquals(
            "Could not resolve cooked executable for selected configuration 'Three Clients': entry 2 Game Win64.",
            thrown.message,
        )
    }

    @Test
    fun `missing package directory reports settings-specific validation message`() {
        val state = settingsState()

        assertEquals(
            "Package directory is not configured; set it in Tools > UnrealHelper before launching cooked builds.",
            quickLaunchValidationError(state, packageDirectory = ""),
        )
    }

    @Test
    fun `resolved executable builds launch key and command line with entry and global args`() {
        val state = settingsState()
        val executable = regularFile("Windows/MyGame.exe")
        val workingDirectory = Files.createDirectory(packageDirectory().resolve("Working"))
        state.activeCommandLine = "-log \"-ExecCmds=stat fps\""
        val configuration = TargetPlatformConfiguration(
            name = "Default",
            entries = listOf(
                TargetPlatformEntry(
                    targetType = "Game",
                    platform = "Win64",
                    workingDirectory = workingDirectory.toString(),
                    arguments = "-windowed -resx=1280",
                ),
            ),
        )

        val launch = createQuickLaunchCommands(
            state = state,
            configuration = configuration,
            packageDirectory = packageDirectory(),
            resolveExecutable = { _, _, _ -> executable },
        ).single()

        assertEquals(QuickLaunchKey("Default", 0, "Game", "Win64"), launch.key)
        assertEquals(executable.toString(), launch.commandLine.exePath)
        assertEquals(workingDirectory, launch.commandLine.workingDirectory)
        assertEquals(
            listOf("-windowed", "-resx=1280", "-log", "-ExecCmds=stat fps"),
            launch.commandLine.parametersList.list,
        )
    }

    @Test
    fun `resolved launch command from entry does not create persistent profile`() {
        val state = settingsState()
        val executable = regularFile("Windows/MyGame.exe")
        state.quickLaunchProfiles = mutableListOf()
        val configuration = TargetPlatformConfiguration(
            name = "Default",
            entries = listOf(TargetPlatformEntry(targetType = "Game", platform = "Win64")),
        )

        val launch = createQuickLaunchCommands(
            state = state,
            configuration = configuration,
            packageDirectory = packageDirectory(),
            resolveExecutable = { _, _, _ -> executable },
        ).single()

        assertEquals(QuickLaunchKey("Default", 0, "Game", "Win64"), launch.key)
        assertEquals(executable.toString(), launch.commandLine.exePath)
        assertTrue(state.quickLaunchProfiles.isEmpty())
    }

    @Test
    fun `unique build environment target resolves executable by target name`() {
        val state = settingsState()
        state.discoveredTargets = mutableListOf(
            UnrealTargetState().also {
                it.name = "MyGameClient"
                it.type = "Client"
                it.usesUniqueBuildEnvironment = true
            },
        )
        val executable = regularFile("Windows/MyGameClient.exe")
        val configuration = TargetPlatformConfiguration(
            name = "Default",
            entries = listOf(TargetPlatformEntry(targetType = "Client", platform = "Win64")),
        )

        val launch = createQuickLaunchCommands(
            state = state,
            configuration = configuration,
            packageDirectory = packageDirectory(),
        ).single()

        assertEquals(executable.toString(), launch.commandLine.exePath)
    }

    @Test
    fun `relative executable and working directory overrides resolve from project root`() {
        val workspaceRoot = temp.newFolder("Workspace").toPath()
        val state = settingsState(workspaceRoot)
        val executable = regularFile(workspaceRoot, "Packages/Windows/MyGame.exe")
        val workingDirectory = Files.createDirectories(workspaceRoot.resolve("Saved/Launch"))
        val configuration = TargetPlatformConfiguration(
            name = "Default",
            entries = listOf(
                TargetPlatformEntry(
                    targetType = "Game",
                    platform = "Win64",
                    executablePath = "Packages/Windows/MyGame.exe",
                    workingDirectory = "Saved/Launch",
                ),
            ),
        )

        val launch = createQuickLaunchCommands(
            state = state,
            configuration = configuration,
            packageDirectory = workspaceRoot.resolve("Packages"),
        ).single()

        assertEquals(executable.toString(), launch.commandLine.exePath)
        assertEquals(workingDirectory, launch.commandLine.workingDirectory)
    }

    @Test
    fun `stop selection chooses running keys from selected configuration`() {
        val first = QuickLaunchKey("Three Clients", 0, "Game", "Win64")
        val second = QuickLaunchKey("Three Clients", 1, "Game", "Win64")
        val other = QuickLaunchKey("Other", 0, "Server", "Linux")
        val configuration = TargetPlatformConfiguration(
            name = "Three Clients",
            entries = listOf(
                TargetPlatformEntry(targetType = "Game", platform = "Win64", arguments = "-first"),
                TargetPlatformEntry(targetType = "Game", platform = "Win64", arguments = "-second"),
            ),
        )

        val selection = stopLaunchSelection(
            selectedKeys = selectedQuickLaunchKeys(configuration),
            runningKeys = setOf(first, other),
        )

        assertEquals(listOf(first, second), selectedQuickLaunchKeys(configuration))
        assertEquals(UnrealStopLaunchSelection(keys = setOf(first), stopAll = false), selection)
    }

    @Test
    fun `stop selection falls back to all running keys when no selected pair is running`() {
        val game = QuickLaunchKey("Game Config", 0, "Game", "Win64")
        val client = QuickLaunchKey("Client Config", 0, "Client", "Win64")
        val server = QuickLaunchKey("Server Config", 0, "Server", "Linux")

        val selection = stopLaunchSelection(
            selectedKeys = listOf(server),
            runningKeys = setOf(game, client),
        )

        assertEquals(UnrealStopLaunchSelection(keys = setOf(game, client), stopAll = true), selection)
    }

    private fun settingsState(workspaceRoot: Path = Path.of("/Workspace/MyGame")): UnrealHelperSettingsState =
        UnrealHelperSettingsState().also {
            it.workspaceRoot = workspaceRoot.toString()
            it.uprojectPath = workspaceRoot.resolve("MyGame.uproject").toString()
            it.packageDirectory = workspaceRoot.resolve("Packages").toString()
        }

    private fun packageDirectory(): Path = temp.root.toPath()

    private fun regularFile(relativePath: String): Path {
        val path = packageDirectory().resolve(relativePath)
        Files.createDirectories(path.parent)
        return Files.createFile(path)
    }

    private fun regularFile(root: Path, relativePath: String): Path {
        val path = root.resolve(relativePath)
        Files.createDirectories(path.parent)
        return Files.createFile(path)
    }
}
