package com.cmroche.unrealhelper.launch

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class CookedExecutableResolverTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `override executable path takes precedence over package candidates`() {
        regularFile("Windows/MyGame.exe")
        val override = regularFile("Custom/Override.exe")
        val profile = QuickLaunchProfileState(
            platform = "Win64",
            executablePath = override.toString(),
        )

        assertEquals(override, CookedExecutableResolver.resolve(profile, packageDirectory(), "MyGame"))
    }

    @Test
    fun `win64 resolves Windows executable candidate`() {
        val executable = regularFile("Windows/MyGame.exe")

        assertEquals(
            executable,
            CookedExecutableResolver.resolve(QuickLaunchProfileState(platform = "Win64"), packageDirectory(), "MyGame"),
        )
    }

    @Test
    fun `win64 falls back to WindowsNoEditor executable candidate`() {
        val executable = regularFile("WindowsNoEditor/MyGame.exe")

        assertEquals(
            executable,
            CookedExecutableResolver.resolve(QuickLaunchProfileState(platform = "Win64"), packageDirectory(), "MyGame"),
        )
    }

    @Test
    fun `mac resolves app bundle executable candidate`() {
        val executable = regularFile("Mac/MyGame.app/Contents/MacOS/MyGame")

        assertEquals(
            executable,
            CookedExecutableResolver.resolve(QuickLaunchProfileState(platform = "Mac"), packageDirectory(), "MyGame"),
        )
    }

    @Test
    fun `linux resolves platform executable candidate`() {
        val executable = regularFile("Linux/MyGame")

        assertEquals(
            executable,
            CookedExecutableResolver.resolve(QuickLaunchProfileState(platform = "Linux"), packageDirectory(), "MyGame"),
        )
    }

    @Test
    fun `unknown platform resolves fallback platform executable candidate`() {
        val executable = regularFile("Android/MyGame")

        assertEquals(
            executable,
            CookedExecutableResolver.resolve(QuickLaunchProfileState(platform = "Android"), packageDirectory(), "MyGame"),
        )
    }

    @Test
    fun `project name is derived from uproject path`() {
        assertEquals(
            "MyGame",
            CookedExecutableResolver.projectName(Path.of("/Workspace/My Game/MyGame.uproject")),
        )
    }

    @Test
    fun `launch command uses executable parent as working directory and combines parsed arguments`() {
        val executable = regularFile("Linux/MyGame")
        val profile = QuickLaunchProfileState(arguments = "-ProfileArg=\"two words\" -log")

        val commandLine = CookedExecutableResolver.launchCommand(
            profile = profile,
            executable = executable,
            globalArgs = "-GlobalArg=1 \"-ExecCmds=stat fps\"",
        )

        assertEquals(executable.toString(), commandLine.exePath)
        assertEquals(executable.parent, commandLine.workingDirectory)
        assertEquals(
            listOf("-ProfileArg=two words", "-log", "-GlobalArg=1", "-ExecCmds=stat fps"),
            commandLine.parametersList.list,
        )
    }

    @Test
    fun `launch command uses configured working directory when present`() {
        val executable = regularFile("Linux/MyGame")
        val workingDirectory = Files.createDirectory(packageDirectory().resolve("WorkingDir"))
        val profile = QuickLaunchProfileState(workingDirectory = workingDirectory.toString())

        val commandLine = CookedExecutableResolver.launchCommand(profile, executable, globalArgs = "")

        assertEquals(workingDirectory, commandLine.workingDirectory)
    }

    private fun packageDirectory(): Path = temp.root.toPath()

    private fun regularFile(relativePath: String): Path {
        val path = packageDirectory().resolve(relativePath)
        Files.createDirectories(path.parent)
        return Files.createFile(path)
    }
}
