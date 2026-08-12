package com.cmroche.unrealhelper.config

import com.cmroche.unrealhelper.settings.UnrealTargetState
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal interface TargetPlatformConfigurationStorage {
    fun load(
        path: Path,
        discoveredTargets: List<UnrealTargetState> = emptyList(),
    ): TargetPlatformConfigurationLoadResult

    fun save(path: Path, file: TargetPlatformConfigurationsFile): TargetPlatformConfigurationLoadResult.Loaded
}

class TargetPlatformConfigurationStore(
    private val json: Json = DefaultJson,
) : TargetPlatformConfigurationStorage {
    override fun load(
        path: Path,
        discoveredTargets: List<UnrealTargetState>,
    ): TargetPlatformConfigurationLoadResult {
        if (!Files.exists(path)) {
            return TargetPlatformConfigurationLoadResult.Missing(path)
        }

        return try {
            val contents = Files.readString(path)
            val version = json.parseToJsonElement(contents).jsonObject["version"]?.jsonPrimitive?.int
                ?: throw SerializationException("Target & Platform configuration version is missing")
            val decoded = when (version) {
                1 -> migrateLegacyTargetPlatformFile(
                    json.decodeFromString(LegacyTargetPlatformConfigurationsFile.serializer(), contents),
                    discoveredTargets,
                )
                TargetPlatformConfigurationsFile.CurrentVersion ->
                    json.decodeFromString(TargetPlatformConfigurationsFile.serializer(), contents).normalized()
                else -> return TargetPlatformConfigurationLoadResult.Malformed(
                    path,
                    "Unsupported Target & Platform configuration version $version",
                )
            }
            TargetPlatformConfigurationLoadResult.Loaded(
                path,
                decoded,
            )
        } catch (exception: SerializationException) {
            TargetPlatformConfigurationLoadResult.Malformed(path, exception.message ?: exception.javaClass.simpleName)
        } catch (exception: IllegalArgumentException) {
            TargetPlatformConfigurationLoadResult.Malformed(path, exception.message ?: exception.javaClass.simpleName)
        } catch (exception: IOException) {
            TargetPlatformConfigurationLoadResult.Malformed(path, exception.message ?: exception.javaClass.simpleName)
        }
    }

    override fun save(path: Path, file: TargetPlatformConfigurationsFile): TargetPlatformConfigurationLoadResult.Loaded {
        val parent = path.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }

        val tempPath = Files.createTempFile(parent ?: Path.of("."), "${path.fileName}.", ".tmp")
        val current = file.normalized().copy(version = TargetPlatformConfigurationsFile.CurrentVersion)
        try {
            Files.writeString(tempPath, json.encodeToString(TargetPlatformConfigurationsFile.serializer(), current))
            try {
                Files.move(tempPath, path, REPLACE_EXISTING, ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempPath, path, REPLACE_EXISTING)
            }
        } finally {
            try {
                Files.deleteIfExists(tempPath)
            } catch (_: IOException) {
            }
        }

        return TargetPlatformConfigurationLoadResult.Loaded(
            path = path,
            file = current,
        )
    }

    companion object {
        val DefaultJson: Json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

sealed interface TargetPlatformConfigurationLoadResult {
    val path: Path

    data class Loaded(
        override val path: Path,
        val file: TargetPlatformConfigurationsFile,
    ) : TargetPlatformConfigurationLoadResult

    data class Missing(override val path: Path) : TargetPlatformConfigurationLoadResult

    data class Malformed(
        override val path: Path,
        val message: String,
    ) : TargetPlatformConfigurationLoadResult
}
