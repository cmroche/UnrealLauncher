package com.cmroche.unrealhelper.actions

import com.cmroche.unrealhelper.launch.QuickLaunchKey
import com.cmroche.unrealhelper.launch.QuickLaunchProfileState
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `selected target and platform pairs build expected launch options`() {
        val state = settingsState()
        state.selectedTargetTypes = mutableListOf(" Game ", "Server", "Game", "")
        state.selectedPlatforms = mutableListOf(" Win64 ", "Linux", "Win64", "")

        val options = createQuickLaunchOptions(
            state = state,
            packageDirectory = packageDirectory(),
            resolveExecutable = { profile, _, _ ->
                packageDirectory().resolve("${profile.targetType}-${profile.platform}")
            },
        )

        assertEquals(
            listOf(
                QuickLaunchKey("Game", "Win64"),
                QuickLaunchKey("Game", "Linux"),
                QuickLaunchKey("Server", "Win64"),
                QuickLaunchKey("Server", "Linux"),
            ),
            options.map { it.key },
        )
        assertEquals(
            listOf(
                "Launch Game Win64",
                "Launch Game Linux",
                "Launch Server Win64",
                "Launch Server Linux",
            ),
            options.map { it.text },
        )
        assertTrue(options.all { it.isEnabled })
    }

    @Test
    fun `unresolved executable produces disabled launch option without creating profile`() {
        val state = settingsState()
        state.quickLaunchProfiles = mutableListOf()

        val options = createQuickLaunchOptions(
            state = state,
            packageDirectory = packageDirectory(),
            resolveExecutable = { _, _, _ -> null },
        )

        assertEquals(listOf(QuickLaunchKey("Game", "Win64")), options.map { it.key })
        assertFalse(options.single().isEnabled)
        assertNull(options.single().executable)
        assertTrue(state.quickLaunchProfiles.isEmpty())
    }

    @Test
    fun `resolved executable builds launch key and command line with profile and global args`() {
        val state = settingsState()
        val executable = regularFile("Windows/MyGame.exe")
        val workingDirectory = Files.createDirectory(packageDirectory().resolve("Working"))
        state.activeCommandLine = "-log \"-ExecCmds=stat fps\""
        state.quickLaunchProfiles = mutableListOf(
            QuickLaunchProfileState(
                name = "Windows Game",
                targetType = "Game",
                platform = "Win64",
                workingDirectory = workingDirectory.toString(),
                arguments = "-windowed -resx=1280",
            ),
        )

        val launch = createQuickLaunchCommand(
            state = state,
            key = QuickLaunchKey("Game", "Win64"),
            packageDirectory = packageDirectory(),
            resolveExecutable = { _, _, _ -> executable },
        )

        assertEquals(QuickLaunchKey("Game", "Win64"), launch?.key)
        assertEquals(executable.toString(), launch?.commandLine?.exePath)
        assertEquals(workingDirectory, launch?.commandLine?.workingDirectory)
        assertEquals(
            listOf("-windowed", "-resx=1280", "-log", "-ExecCmds=stat fps"),
            launch?.commandLine?.parametersList?.list,
        )
    }

    @Test
    fun `resolved launch command without existing profile does not create persistent profile`() {
        val state = settingsState()
        val executable = regularFile("Windows/MyGame.exe")
        state.quickLaunchProfiles = mutableListOf()

        val launch = createQuickLaunchCommand(
            state = state,
            key = QuickLaunchKey("Game", "Win64"),
            packageDirectory = packageDirectory(),
            resolveExecutable = { _, _, _ -> executable },
        )

        assertEquals(QuickLaunchKey("Game", "Win64"), launch?.key)
        assertEquals(executable.toString(), launch?.commandLine?.exePath)
        assertTrue(state.quickLaunchProfiles.isEmpty())
    }

    @Test
    fun `launch execution notifies when command creation throws`() {
        val state = settingsState()
        val option = UnrealQuickLaunchOption(
            key = QuickLaunchKey("Game", "Win64"),
            text = "Launch Game Win64",
            executable = packageDirectory().resolve("Windows/MyGame.exe"),
        )
        val notifications = mutableListOf<String>()

        executeQuickLaunchOption(
            option = option,
            state = state,
            packageDirectory = packageDirectory().toString(),
            launch = { _, _ -> error("launch should not run") },
            notifyError = { notifications += it },
            resolveExecutable = { _, _, _ -> throw IllegalArgumentException("bad executable path") },
        )

        assertEquals(listOf("Failed to launch Game Win64: bad executable path"), notifications)
    }

    @Test
    fun `launch execution notifies when package directory path is invalid`() {
        val state = settingsState()
        val option = UnrealQuickLaunchOption(
            key = QuickLaunchKey("Game", "Win64"),
            text = "Launch Game Win64",
            executable = packageDirectory().resolve("Windows/MyGame.exe"),
        )
        val notifications = mutableListOf<String>()

        executeQuickLaunchOption(
            option = option,
            state = state,
            packageDirectory = "\u0000",
            launch = { _, _ -> error("launch should not run") },
            notifyError = { notifications += it },
        )

        assertEquals(1, notifications.size)
        assertTrue(notifications.single().startsWith("Failed to launch Game Win64:"))
    }

    @Test
    fun `launch execution rethrows process cancellation`() {
        val state = settingsState()
        val option = UnrealQuickLaunchOption(
            key = QuickLaunchKey("Game", "Win64"),
            text = "Launch Game Win64",
            executable = packageDirectory().resolve("Windows/MyGame.exe"),
        )

        assertThrows(ProcessCanceledException::class.java) {
            executeQuickLaunchOption(
                option = option,
                state = state,
                packageDirectory = packageDirectory().toString(),
                launch = { _, _ -> error("launch should not run") },
                notifyError = { error("cancellation should not notify") },
                resolveExecutable = { _, _, _ -> throw ProcessCanceledException() },
            )
        }
    }

    @Test
    fun `stop selection chooses selected running keys`() {
        val game = QuickLaunchKey("Game", "Win64")
        val server = QuickLaunchKey("Server", "Linux")
        val client = QuickLaunchKey("Client", "Win64")

        val selection = stopLaunchSelection(
            selectedKeys = listOf(game, server),
            runningKeys = setOf(game, client),
        )

        assertEquals(UnrealStopLaunchSelection(keys = setOf(game), stopAll = false), selection)
    }

    @Test
    fun `stop selection falls back to all running keys when no selected pair is running`() {
        val game = QuickLaunchKey("Game", "Win64")
        val client = QuickLaunchKey("Client", "Win64")
        val server = QuickLaunchKey("Server", "Linux")

        val selection = stopLaunchSelection(
            selectedKeys = listOf(server),
            runningKeys = setOf(game, client),
        )

        assertEquals(UnrealStopLaunchSelection(keys = setOf(game, client), stopAll = true), selection)
    }

    private fun settingsState(): UnrealHelperSettingsState =
        UnrealHelperSettingsState().also {
            it.uprojectPath = "/Workspace/MyGame/MyGame.uproject"
            it.packageDirectory = packageDirectory().toString()
            it.selectedTargetTypes = mutableListOf("Game")
            it.selectedPlatforms = mutableListOf("Win64")
        }

    private fun packageDirectory(): Path = temp.root.toPath()

    private fun regularFile(relativePath: String): Path {
        val path = packageDirectory().resolve(relativePath)
        Files.createDirectories(path.parent)
        return Files.createFile(path)
    }
}
