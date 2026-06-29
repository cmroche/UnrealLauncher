package com.cmroche.unrealhelper.config

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
    fun `configuration file serializes with user-visible names and entries`() {
        val file = TargetPlatformConfigurationsFile(
            configurations = listOf(
                TargetPlatformConfiguration(
                    name = "Client and Server",
                    entries = listOf(
                        TargetPlatformEntry(
                            targetType = "Client",
                            platform = "Win64",
                            arguments = "-server=127.0.0.1",
                            executablePath = "Binaries/Win64/TestClient.exe",
                            workingDirectory = "Saved/StagedBuilds/Windows",
                        ),
                        TargetPlatformEntry(targetType = "Server", platform = "Win64", arguments = "/Game/Maps/TestMap"),
                    ),
                ),
            ),
        )

        val encoded = json.encodeToString(TargetPlatformConfigurationsFile.serializer(), file)
        val decoded = json.decodeFromString(TargetPlatformConfigurationsFile.serializer(), encoded)

        assertEquals(file, decoded)
        assertEquals(1, decoded.version)
        assertEquals("Client and Server", decoded.configurations.single().name)
        assertEquals(listOf("Client", "Server"), decoded.configurations.single().entries.map { it.targetType })
        assertEquals(listOf("Win64", "Win64"), decoded.configurations.single().entries.map { it.platform })
    }

    @Test
    fun `configuration file decodes from the expected json shape`() {
        val decoded = json.decodeFromString(
            TargetPlatformConfigurationsFile.serializer(),
            """
            {
              "version": 1,
              "configurations": [
                {
                  "name": "Client and Server",
                  "entries": [
                    {
                      "targetType": "Client",
                      "platform": "Win64",
                      "arguments": "-server=127.0.0.1",
                      "executablePath": "Binaries/Win64/TestClient.exe",
                      "workingDirectory": "Saved/StagedBuilds/Windows"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(
            TargetPlatformConfigurationsFile(
                version = 1,
                configurations = listOf(
                    TargetPlatformConfiguration(
                        name = "Client and Server",
                        entries = listOf(
                            TargetPlatformEntry(
                                targetType = "Client",
                                platform = "Win64",
                                arguments = "-server=127.0.0.1",
                                executablePath = "Binaries/Win64/TestClient.exe",
                                workingDirectory = "Saved/StagedBuilds/Windows",
                            ),
                        ),
                    ),
                ),
            ),
            decoded,
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
    fun `store loads and normalizes shared file`() {
        val path = targetPlatformConfigurationsPath()
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            """
            {
              "version": 1,
              "configurations": [
                {
                  "name": " Client and Server ",
                  "entries": [
                    {
                      "targetType": " Client ",
                      "platform": " Win64 ",
                      "arguments": " -server=127.0.0.1 ",
                      "executablePath": " Binaries/Win64/TestClient.exe ",
                      "workingDirectory": " Saved/StagedBuilds/Windows "
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val result = TargetPlatformConfigurationStore().load(path)

        assertTrue(result is TargetPlatformConfigurationLoadResult.Loaded)
        result as TargetPlatformConfigurationLoadResult.Loaded
        assertEquals(path, result.path)
        assertEquals(
            TargetPlatformConfigurationsFile(
                configurations = listOf(
                    TargetPlatformConfiguration(
                        name = "Client and Server",
                        entries = listOf(
                            TargetPlatformEntry(
                                targetType = "Client",
                                platform = "Win64",
                                arguments = "-server=127.0.0.1",
                                executablePath = "Binaries/Win64/TestClient.exe",
                                workingDirectory = "Saved/StagedBuilds/Windows",
                            ),
                        ),
                    ),
                ),
            ),
            result.file,
        )
    }

    @Test
    fun `store reports malformed JSON with file path`() {
        val path = targetPlatformConfigurationsPath()
        Files.createDirectories(path.parent)
        Files.writeString(path, "{")

        val result = TargetPlatformConfigurationStore().load(path)

        assertTrue(result is TargetPlatformConfigurationLoadResult.Malformed)
        result as TargetPlatformConfigurationLoadResult.Malformed
        assertEquals(path, result.path)
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun `store creates parent directory and writes JSON`() {
        val path = targetPlatformConfigurationsPath()
        val file = TargetPlatformConfigurationsFile(
            configurations = listOf(
                TargetPlatformConfiguration(
                    name = "Client and Server",
                    entries = listOf(
                        TargetPlatformEntry(
                            targetType = "Client",
                            platform = "Win64",
                            arguments = "-server=127.0.0.1",
                            executablePath = "Binaries/Win64/TestClient.exe",
                            workingDirectory = "Saved/StagedBuilds/Windows",
                        ),
                    ),
                ),
            ),
        )

        TargetPlatformConfigurationStore().save(path, file)

        assertTrue(Files.exists(path))
        assertEquals(".unrealhelper", path.parent.fileName.toString())
        val result = TargetPlatformConfigurationStore().load(path)
        assertTrue(result is TargetPlatformConfigurationLoadResult.Loaded)
        result as TargetPlatformConfigurationLoadResult.Loaded
        assertEquals(file, result.file)
    }

    private fun targetPlatformConfigurationsPath(): Path =
        temp.root.toPath()
            .resolve(".unrealhelper")
            .resolve("target-platforms.json")
}
