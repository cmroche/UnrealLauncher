package com.cmroche.unrealhelper.config

import com.cmroche.unrealhelper.settings.UnrealTargetState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class TargetPlatformConfigurationStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `configuration file serializes version 2 build target entries`() {
        val current = TargetPlatformEntry(
            targetName = "LyraClient",
            platform = "Win64",
            arguments = "-windowed",
            cookOnLaunch = true,
            incrementalCookOnLaunch = true,
        )
        val file = TargetPlatformConfigurationsFile(
            configurations = listOf(TargetPlatformConfiguration("Client", listOf(current))),
        )

        val encoded = json.encodeToString(TargetPlatformConfigurationsFile.serializer(), file)
        val decoded = json.decodeFromString(TargetPlatformConfigurationsFile.serializer(), encoded)

        assertEquals(2, decoded.version)
        assertEquals(current, decoded.configurations.single().entries.single())
        assertFalse(encoded.contains("targetType"))
        assertFalse(encoded.contains("executablePath"))
        assertFalse(encoded.contains("workingDirectory"))
    }

    @Test
    fun `version 1 entry migrates a single matching target and discards paths`() {
        val result = loadLegacy(
            targetType = " Client ",
            targets = listOf(target(" LyraClient ", "Client")),
        )

        val entry = result.file.configurations.single().entries.single()
        assertEquals("LyraClient", entry.targetName)
        assertEquals("Win64", entry.platform)
        assertEquals("-log", entry.arguments)
        assertFalse(entry.cookOnLaunch)
        assertFalse(entry.incrementalCookOnLaunch)
    }

    @Test
    fun `version 1 entry with two matching targets migrates to blank target name`() {
        val result = loadLegacy(
            targetType = "Client",
            targets = listOf(target("LyraClient", "Client"), target("ShooterClient", " Client ")),
        )

        assertEquals("", result.file.configurations.single().entries.single().targetName)
    }

    @Test
    fun `version 1 entry with no matching target migrates to visible invalid marker`() {
        val result = loadLegacy(
            targetType = "Server",
            targets = listOf(target("LyraClient", "Client")),
        )

        assertEquals(
            "Missing legacy Server target",
            result.file.configurations.single().entries.single().targetName,
        )
    }

    @Test
    fun `store returns Missing when shared file does not exist`() {
        val path = targetPlatformConfigurationsPath()

        assertEquals(
            TargetPlatformConfigurationLoadResult.Missing(path),
            TargetPlatformConfigurationStore().load(path),
        )
    }

    @Test
    fun `store loads and normalizes version 2 fields only`() {
        val path = targetPlatformConfigurationsPath()
        write(
            path,
            """
            {
              "version": 2,
              "configurations": [{
                "name": " Client ",
                "entries": [{
                  "targetName": " LyraClient ",
                  "platform": " Win64 ",
                  "arguments": " -log ",
                  "targetType": "Client",
                  "executablePath": "/tmp/LyraClient.exe",
                  "workingDirectory": "/tmp"
                }]
              }]
            }
            """.trimIndent(),
        )

        val result = TargetPlatformConfigurationStore().load(path)

        assertTrue(result is TargetPlatformConfigurationLoadResult.Loaded)
        result as TargetPlatformConfigurationLoadResult.Loaded
        assertEquals("Client", result.file.configurations.single().name)
        assertEquals(
            TargetPlatformEntry(targetName = "LyraClient", platform = "Win64", arguments = "-log"),
            result.file.configurations.single().entries.single(),
        )
    }

    @Test
    fun `store reports unsupported version`() {
        val path = targetPlatformConfigurationsPath()
        write(path, """{"version": 3, "configurations": []}""")

        val result = TargetPlatformConfigurationStore().load(path)

        assertEquals(
            TargetPlatformConfigurationLoadResult.Malformed(
                path,
                "Unsupported Target & Platform configuration version 3",
            ),
            result,
        )
    }

    @Test
    fun `store reports malformed JSON with file path`() {
        val path = targetPlatformConfigurationsPath()
        write(path, "{")

        val result = TargetPlatformConfigurationStore().load(path)

        assertTrue(result is TargetPlatformConfigurationLoadResult.Malformed)
        result as TargetPlatformConfigurationLoadResult.Malformed
        assertEquals(path, result.path)
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun `store always saves current version and creates parent directory`() {
        val path = targetPlatformConfigurationsPath()
        val file = configurationFile("Client", "LyraClient", "Win64").copy(version = 1)

        TargetPlatformConfigurationStore().save(path, file)

        assertTrue(Files.exists(path))
        val encoded = Files.readString(path)
        assertTrue(encoded.contains("\"version\": 2"))
        val result = TargetPlatformConfigurationStore().load(path)
        assertTrue(result is TargetPlatformConfigurationLoadResult.Loaded)
        result as TargetPlatformConfigurationLoadResult.Loaded
        assertEquals(2, result.file.version)
    }

    @Test
    fun `store saves parentless path`() {
        val path = Path.of("target-platforms.json")
        val file = configurationFile("Game Win64", "MyGame", "Win64")

        try {
            TargetPlatformConfigurationStore().save(path, file)
            assertTrue(TargetPlatformConfigurationStore().load(path) is TargetPlatformConfigurationLoadResult.Loaded)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun loadLegacy(
        targetType: String,
        targets: List<UnrealTargetState>,
    ): TargetPlatformConfigurationLoadResult.Loaded {
        val path = targetPlatformConfigurationsPath()
        write(
            path,
            """
            {
              "version": 1,
              "configurations": [{
                "name": " Legacy ",
                "entries": [{
                  "targetType": "$targetType",
                  "platform": " Win64 ",
                  "arguments": " -log ",
                  "executablePath": "/tmp/Legacy.exe",
                  "workingDirectory": "/tmp/Legacy"
                }]
              }]
            }
            """.trimIndent(),
        )
        val result = TargetPlatformConfigurationStore().load(path, targets)
        assertTrue(result is TargetPlatformConfigurationLoadResult.Loaded)
        return result as TargetPlatformConfigurationLoadResult.Loaded
    }

    private fun target(name: String, type: String): UnrealTargetState =
        UnrealTargetState().also {
            it.name = name
            it.type = type
        }

    private fun write(path: Path, contents: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, contents)
    }

    private fun targetPlatformConfigurationsPath(): Path =
        temp.root.toPath().resolve(".unrealhelper").resolve("target-platforms.json")

    private fun configurationFile(name: String, targetName: String, platform: String): TargetPlatformConfigurationsFile =
        TargetPlatformConfigurationsFile(
            configurations = listOf(
                TargetPlatformConfiguration(
                    name = name,
                    entries = listOf(TargetPlatformEntry(targetName = targetName, platform = platform)),
                ),
            ),
        )
}
