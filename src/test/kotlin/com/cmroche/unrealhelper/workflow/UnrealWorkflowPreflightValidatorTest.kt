package com.cmroche.unrealhelper.workflow

import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.config.TargetPlatformEntry
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.cmroche.unrealhelper.settings.UnrealTargetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class UnrealWorkflowPreflightValidatorTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `aggregates project workspace engine and row errors with configuration identity`() {
        val state = UnrealHelperSettingsState().also {
            it.uprojectPath = ""
            it.workspaceRoot = ""
            it.engineRoot = ""
        }
        val configuration = TargetPlatformConfiguration(
            name = "Broken Client",
            entries = listOf(TargetPlatformEntry(targetName = "MissingTarget", platform = "MissingPlatform")),
        )

        val errors = UnrealWorkflowPreflightValidator().validate(
            UnrealWorkflowRequest.BUILD,
            configuration,
            state,
            projectBasePath = null,
        )

        assertEquals(4, errors.size)
        assertTrue(errors.any { it.contains("Project file is not configured") })
        assertTrue(errors.any { it.contains("Workspace root is not configured") })
        assertTrue(errors.any { it.contains("Engine root is not configured") })
        assertTrue(errors.any {
            it.contains("configuration 'Broken Client'") &&
                it.contains("Entry 1 MissingTarget / MissingPlatform") &&
                it.contains("build target is not discovered") &&
                it.contains("platform is not discovered")
        })
    }

    @Test
    fun `build requires UBT but not UAT or package destination`() {
        val fixture = fixture(createUbt = false)
        fixture.state.packageDirectory = temp.root.toPath().resolve("missing-package").toString()

        val errors = validator().validate(
            UnrealWorkflowRequest.BUILD,
            fixture.configuration,
            fixture.state,
            fixture.workspace.toString(),
        )

        assertEquals(1, errors.size)
        assertTrue(errors.single().startsWith("UnrealBuildTool was not found at "))
    }

    @Test
    fun `cook requires UAT only`() {
        val fixture = fixture(createUbt = false, createUat = false)

        val errors = validator().validate(
            UnrealWorkflowRequest.COOK,
            fixture.configuration,
            fixture.state,
            fixture.workspace.toString(),
        )

        assertEquals(1, errors.size)
        assertTrue(errors.single().startsWith("RunUAT was not found at "))
    }

    @Test
    fun `UAT workflows reject editor target before execution`() {
        val fixture = fixture()
        fixture.state.discoveredTargets.single().also {
            it.name = "LyraEditor"
            it.type = "Editor"
        }
        listOf(
            UnrealWorkflowRequest.COOK to false,
            UnrealWorkflowRequest.PACKAGE to false,
            UnrealWorkflowRequest.LAUNCH to true,
        ).forEach { (request, cookOnLaunch) ->
            val configuration = TargetPlatformConfiguration(
                name = "Editor",
                entries = listOf(
                    TargetPlatformEntry(
                        targetName = "LyraEditor",
                        platform = "Win64",
                        cookOnLaunch = cookOnLaunch,
                    ),
                ),
            )

            val errors = validator().validate(
                request,
                configuration,
                fixture.state,
                fixture.workspace.toString(),
            )

            assertEquals(
                "$request errors",
                listOf(
                    "Editor target 'LyraEditor' cannot be cooked, staged, or packaged; " +
                        "choose a Game, Client, or Server target",
                ),
                errors,
            )
        }
    }

    @Test
    fun `package creates a missing destination after validating its writable parent`() {
        val fixture = fixture()
        val destination = fixture.workspace.resolve("missing-package")
        fixture.state.packageDirectory = destination.toString()

        val errors = validator().validate(
            UnrealWorkflowRequest.PACKAGE,
            fixture.configuration,
            fixture.state,
            fixture.workspace.toString(),
        )

        assertEquals(emptyList<String>(), errors)
        assertTrue(Files.isDirectory(destination))
    }

    @Test
    fun `package rejects a destination whose nearest path component is a file`() {
        val fixture = fixture()
        val file = Files.createFile(fixture.workspace.resolve("not-a-directory"))
        fixture.state.packageDirectory = file.resolve("Packages").toString()

        val errors = validator().validate(
            UnrealWorkflowRequest.PACKAGE,
            fixture.configuration,
            fixture.state,
            fixture.workspace.toString(),
        )

        assertTrue(errors.single().startsWith("Package destination is not writable at "))
    }

    @Test
    fun `launch without cook requires UBT but not UAT or package destination`() {
        val fixture = fixture(createUbt = false, createUat = false, cookOnLaunch = false)
        fixture.state.packageDirectory = fixture.workspace.resolve("missing-package").toString()

        val missingUbtErrors = validator().validate(
            UnrealWorkflowRequest.LAUNCH,
            fixture.configuration,
            fixture.state,
            fixture.workspace.toString(),
        )
        assertEquals(1, missingUbtErrors.size)
        assertTrue(missingUbtErrors.single().startsWith("UnrealBuildTool was not found at "))

        createTool(Path.of(fixture.state.engineRoot), ubtRelativePath())
        assertEquals(
            emptyList<String>(),
            validator().validate(
                UnrealWorkflowRequest.LAUNCH,
                fixture.configuration,
                fixture.state,
                fixture.workspace.toString(),
            ),
        )
    }

    @Test
    fun `launch with cook requires UAT`() {
        val fixture = fixture(createUbt = true, createUat = false, cookOnLaunch = true)

        val errors = validator().validate(
            UnrealWorkflowRequest.LAUNCH,
            fixture.configuration,
            fixture.state,
            fixture.workspace.toString(),
        )

        assertEquals(1, errors.size)
        assertTrue(errors.single().startsWith("RunUAT was not found at "))
    }

    @Test
    fun `rejects an empty plan`() {
        val fixture = fixture()
        val validator = UnrealWorkflowPreflightValidator(plan = { request, configuration, state, basePath ->
            UnrealWorkflowPlanner().plan(request, configuration, state, basePath).copy(phases = emptyList())
        })

        assertEquals(
            listOf("Workflow plan for configuration 'Client' has no phases"),
            validator.validate(
                UnrealWorkflowRequest.BUILD,
                fixture.configuration,
                fixture.state,
                fixture.workspace.toString(),
            ),
        )
    }

    @Test
    fun `rejects phases outside canonical order`() {
        val fixture = fixture(cookOnLaunch = true)
        val validator = UnrealWorkflowPreflightValidator(plan = { request, configuration, state, basePath ->
            val valid = UnrealWorkflowPlanner().plan(request, configuration, state, basePath)
            valid.copy(phases = valid.phases.reversed())
        })

        val errors = validator.validate(
            UnrealWorkflowRequest.LAUNCH,
            fixture.configuration,
            fixture.state,
            fixture.workspace.toString(),
        )

        assertEquals(
            listOf("Workflow plan for configuration 'Client' has invalid phase order: LAUNCH, COOK, BUILD"),
            errors,
        )
    }

    @Test
    fun `validates tools from immutable plan environment`() {
        val fixture = fixture()
        val plannedEngineRoot = Files.createDirectories(fixture.workspace.resolve("PlannedEngineRoot"))
        val validator = UnrealWorkflowPreflightValidator(plan = { request, configuration, state, basePath ->
            val valid = UnrealWorkflowPlanner().plan(request, configuration, state, basePath)
            valid.copy(environment = valid.environment.copy(engineRoot = plannedEngineRoot))
        })

        val errors = validator.validate(
            UnrealWorkflowRequest.BUILD,
            fixture.configuration,
            fixture.state,
            fixture.workspace.toString(),
        )

        assertEquals(1, errors.size)
        assertEquals(
            "UnrealBuildTool was not found at ${plannedEngineRoot.resolve(ubtRelativePath())}",
            errors.single(),
        )
    }

    @Test
    fun `malformed package destination is irrelevant without package phase`() {
        listOf(
            UnrealWorkflowRequest.BUILD,
            UnrealWorkflowRequest.COOK,
            UnrealWorkflowRequest.LAUNCH,
        ).forEach { request ->
            val fixture = fixture(cookOnLaunch = false)
            fixture.state.packageDirectory = "bad\u0000package"

            assertEquals(
                "$request errors",
                emptyList<String>(),
                validator().validate(request, fixture.configuration, fixture.state, fixture.workspace.toString()),
            )
        }
    }

    @Test
    fun `malformed package destination blocks package`() {
        val fixture = fixture()
        fixture.state.packageDirectory = "bad\u0000package"

        val errors = validator().validate(
            UnrealWorkflowRequest.PACKAGE,
            fixture.configuration,
            fixture.state,
            fixture.workspace.toString(),
        )

        assertEquals(listOf("Package destination path is invalid"), errors)
    }

    @Test
    fun `unknown build configuration and unsupported inferred target type aggregate`() {
        val fixture = fixture()
        fixture.state.buildConfiguration = "Profile"
        fixture.state.discoveredTargets.single().type = "Program"

        val errors = validator().validate(
            UnrealWorkflowRequest.BUILD,
            fixture.configuration,
            fixture.state,
            fixture.workspace.toString(),
        )

        assertEquals(2, errors.size)
        assertTrue(errors.any { it == "Build configuration 'Profile' is not supported" })
        assertTrue(errors.any {
            it.contains("Entry 1 LyraClient / Win64") &&
                it.contains("target type 'Program' is not supported; expected Game, Client, Server, or Editor")
        })
    }

    private fun validator(): UnrealWorkflowPreflightValidator = UnrealWorkflowPreflightValidator()

    private fun fixture(
        createUbt: Boolean = true,
        createUat: Boolean = true,
        cookOnLaunch: Boolean = false,
    ): Fixture {
        val workspace = temp.newFolder().toPath()
        val project = Files.createFile(workspace.resolve("Lyra.uproject"))
        val engineRoot = Files.createDirectories(workspace.resolve("EngineRoot"))
        val packageDirectory = Files.createDirectories(workspace.resolve("Packages"))
        if (createUbt) createTool(engineRoot, ubtRelativePath())
        if (createUat) createTool(engineRoot, uatRelativePath())

        val state = UnrealHelperSettingsState().also {
            it.uprojectPath = project.toString()
            it.workspaceRoot = workspace.toString()
            it.engineRoot = engineRoot.toString()
            it.packageDirectory = packageDirectory.toString()
            it.discoveredPlatforms = mutableListOf("Win64")
            it.discoveredTargets = mutableListOf(
                UnrealTargetState().also { target ->
                    target.name = "LyraClient"
                    target.type = "Client"
                },
            )
        }
        val configuration = TargetPlatformConfiguration(
            name = "Client",
            entries = listOf(
                TargetPlatformEntry(
                    targetName = "LyraClient",
                    platform = "Win64",
                    cookOnLaunch = cookOnLaunch,
                ),
            ),
        )
        return Fixture(workspace, state, configuration)
    }

    private fun createTool(engineRoot: Path, relativePath: Path) {
        val path = engineRoot.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.createFile(path)
    }

    private fun ubtRelativePath(): Path = Path.of(
        "Engine",
        "Binaries",
        "DotNET",
        "UnrealBuildTool",
        if (isWindows()) "UnrealBuildTool.exe" else "UnrealBuildTool",
    )

    private fun uatRelativePath(): Path = Path.of(
        "Engine",
        "Build",
        "BatchFiles",
        if (isWindows()) "RunUAT.bat" else "RunUAT.sh",
    )

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private data class Fixture(
        val workspace: Path,
        val state: UnrealHelperSettingsState,
        val configuration: TargetPlatformConfiguration,
    )
}
