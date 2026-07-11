package com.cmroche.unrealhelper.workflow

import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.config.TargetPlatformEntry
import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.cmroche.unrealhelper.settings.UnrealTargetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Path

class UnrealWorkflowPlannerTest {
    private val planner = UnrealWorkflowPlanner()

    @Test
    fun `each button expands to its required phases`() {
        val configuration = configuration(
            entry("LyraClient", "Win64", cookOnLaunch = true),
        )
        val state = state(target("LyraClient", "Client"))

        assertEquals(
            listOf(UnrealPhase.BUILD),
            planner.plan(UnrealWorkflowRequest.BUILD, configuration, state, null).phases.map { it.phase },
        )
        assertEquals(
            listOf(UnrealPhase.COOK),
            planner.plan(UnrealWorkflowRequest.COOK, configuration, state, null).phases.map { it.phase },
        )
        assertEquals(
            listOf(UnrealPhase.BUILD, UnrealPhase.COOK, UnrealPhase.STAGE, UnrealPhase.PACKAGE),
            planner.plan(UnrealWorkflowRequest.PACKAGE, configuration, state, null).phases.map { it.phase },
        )
        assertEquals(
            listOf(UnrealPhase.BUILD, UnrealPhase.COOK, UnrealPhase.LAUNCH),
            planner.plan(UnrealWorkflowRequest.LAUNCH, configuration, state, null).phases.map { it.phase },
        )
    }

    @Test
    fun `three duplicate launch rows build and cook once but launch three times`() {
        val duplicate = entry("LyraClient", "Win64", cookOnLaunch = true)
        val plan = planner.plan(
            UnrealWorkflowRequest.LAUNCH,
            configuration(duplicate, duplicate, duplicate),
            state(target("LyraClient", "Client")),
            null,
        )

        val build = plan.actions<BuildBatch>().single()
        assertEquals(1, build.artifacts.size)
        assertEquals(1, plan.actions<Cook>().size)
        assertEquals(listOf(0, 1, 2), plan.actions<Launch>().map { it.rowIndex })
    }

    @Test
    fun `game client and server remain separate cook artifacts in row order`() {
        val plan = planner.plan(
            UnrealWorkflowRequest.COOK,
            configuration(
                entry("LyraGame", "Win64"),
                entry("LyraClient", "Win64"),
                entry("LyraServer", "Win64"),
            ),
            state(
                target("LyraGame", "Game"),
                target("LyraClient", "Client"),
                target("LyraServer", "Server"),
            ),
            null,
        )

        assertEquals(
            listOf("LyraGame" to "Game", "LyraClient" to "Client", "LyraServer" to "Server"),
            plan.actions<Cook>().map { it.artifact.targetName to it.artifact.targetType },
        )
    }

    @Test
    fun `different exact targets of the same type remain separate artifacts`() {
        val plan = planner.plan(
            UnrealWorkflowRequest.BUILD,
            configuration(entry("LyraClient", "Win64"), entry("ShooterClient", "Win64")),
            state(target("LyraClient", "Client"), target("ShooterClient", "Client")),
            null,
        )

        assertEquals(
            listOf("LyraClient", "ShooterClient"),
            plan.actions<BuildBatch>().single().artifacts.map { it.targetName },
        )
    }

    @Test
    fun `full cook dominates incremental cook for a duplicate artifact`() {
        val plan = planner.plan(
            UnrealWorkflowRequest.LAUNCH,
            configuration(
                entry("LyraClient", "Win64", cookOnLaunch = true, incrementalCookOnLaunch = true),
                entry("LyraClient", "Win64", cookOnLaunch = true),
            ),
            state(target("LyraClient", "Client")),
            null,
        )

        assertEquals(UnrealCookMode.FULL, plan.actions<Cook>().single().mode)
    }

    @Test
    fun `explicit cook and package requests always plan full cooks`() {
        val configuration = configuration(
            entry("LyraClient", "Win64", cookOnLaunch = true, incrementalCookOnLaunch = true),
        )
        val state = state(target("LyraClient", "Client"))

        assertEquals(
            UnrealCookMode.FULL,
            planner.plan(UnrealWorkflowRequest.COOK, configuration, state, null).actions<Cook>().single().mode,
        )
        assertEquals(
            UnrealCookMode.FULL,
            planner.plan(UnrealWorkflowRequest.PACKAGE, configuration, state, null).actions<Cook>().single().mode,
        )
    }

    @Test
    fun `launch captures configuration row and global arguments without deduplicating rows`() {
        val state = state(target("LyraClient", "Client")).also {
            it.activeCommandLine = "-global -trace=cpu"
        }
        val plan = planner.plan(
            UnrealWorkflowRequest.LAUNCH,
            configuration(
                entry("LyraClient", "Win64", arguments = "-first"),
                entry("LyraClient", "Win64", arguments = "-second"),
            ),
            state,
            null,
        )

        assertEquals("Three Clients", plan.configurationName)
        assertEquals("-global -trace=cpu", plan.globalArguments)
        assertEquals(
            listOf(
                Triple(0, "-first", "-global -trace=cpu"),
                Triple(1, "-second", "-global -trace=cpu"),
            ),
            plan.actions<Launch>().map { Triple(it.rowIndex, it.entryArguments, it.globalArguments) },
        )
    }

    @Test
    fun `relative project path is resolved against project base path`() {
        val state = state(target("LyraClient", "Client")).also {
            it.uprojectPath = "Games/Lyra/Lyra.uproject"
        }

        val artifact = planner.plan(
            UnrealWorkflowRequest.BUILD,
            configuration(entry("LyraClient", "Win64")),
            state,
            "/Workspace",
        ).actions<BuildBatch>().single().artifacts.single()

        assertEquals(Path.of("/Workspace/Games/Lyra/Lyra.uproject"), artifact.projectPath)
        assertEquals("Shipping", artifact.buildConfiguration)
    }

    @Test
    fun `unknown build configuration is rejected instead of coerced`() {
        val state = state(target("LyraClient", "Client")).also {
            it.buildConfiguration = "Profile"
        }

        val error = assertThrows(IllegalArgumentException::class.java) {
            planner.plan(
                UnrealWorkflowRequest.BUILD,
                configuration(entry("LyraClient", "Win64")),
                state,
                null,
            )
        }

        assertEquals("Unsupported build configuration 'Profile'", error.message)
    }

    private fun configuration(vararg entries: TargetPlatformEntry) =
        TargetPlatformConfiguration("Three Clients", entries.toList())

    private fun entry(
        targetName: String,
        platform: String,
        arguments: String = "",
        cookOnLaunch: Boolean = false,
        incrementalCookOnLaunch: Boolean = false,
    ) = TargetPlatformEntry(
        targetName = targetName,
        platform = platform,
        arguments = arguments,
        cookOnLaunch = cookOnLaunch,
        incrementalCookOnLaunch = incrementalCookOnLaunch,
    )

    private fun state(vararg targets: UnrealTargetState) = UnrealHelperSettingsState().also {
        it.uprojectPath = "/Workspace/Lyra/Lyra.uproject"
        it.buildConfiguration = "Shipping"
        it.discoveredTargets = targets.toMutableList()
        it.discoveredPlatforms = mutableListOf("Win64")
    }

    private fun target(name: String, type: String) = UnrealTargetState().also {
        it.name = name
        it.type = type
    }

    private inline fun <reified T : UnrealPlannedAction> UnrealExecutionPlan.actions(): List<T> =
        phases.flatMap { it.actions }.filterIsInstance<T>()
}
