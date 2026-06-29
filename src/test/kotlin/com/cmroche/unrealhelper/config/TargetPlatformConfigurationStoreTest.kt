package com.cmroche.unrealhelper.config

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetPlatformConfigurationStoreTest {
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
                        TargetPlatformEntry(targetType = "Client", platform = "Win64", arguments = "-server=127.0.0.1"),
                        TargetPlatformEntry(targetType = "Server", platform = "Win64", arguments = "/Game/Maps/TestMap"),
                    ),
                ),
            ),
        )

        val encoded = json.encodeToString(TargetPlatformConfigurationsFile.serializer(), file)
        val decoded = json.decodeFromString(TargetPlatformConfigurationsFile.serializer(), encoded)

        assertEquals(1, decoded.version)
        assertEquals("Client and Server", decoded.configurations.single().name)
        assertEquals(listOf("Client", "Server"), decoded.configurations.single().entries.map { it.targetType })
        assertEquals(listOf("Win64", "Win64"), decoded.configurations.single().entries.map { it.platform })
    }
}
