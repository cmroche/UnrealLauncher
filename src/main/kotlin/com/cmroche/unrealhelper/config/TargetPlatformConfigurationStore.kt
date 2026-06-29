package com.cmroche.unrealhelper.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class TargetPlatformConfigurationStore(
    private val json: Json = DefaultJson,
) {
    fun load(path: Path): TargetPlatformConfigurationLoadResult {
        if (!Files.exists(path)) {
            return TargetPlatformConfigurationLoadResult.Missing(path)
        }

        return try {
            val decoded = json.decodeFromString(TargetPlatformConfigurationsFile.serializer(), Files.readString(path))
            TargetPlatformConfigurationLoadResult.Loaded(
                path,
                decoded.normalized(),
                Files.getLastModifiedTime(path).toMillis(),
            )
        } catch (exception: SerializationException) {
            TargetPlatformConfigurationLoadResult.Malformed(path, exception.message ?: exception.javaClass.simpleName)
        } catch (exception: IllegalArgumentException) {
            TargetPlatformConfigurationLoadResult.Malformed(path, exception.message ?: exception.javaClass.simpleName)
        } catch (exception: IOException) {
            TargetPlatformConfigurationLoadResult.Malformed(path, exception.message ?: exception.javaClass.simpleName)
        }
    }

    fun save(path: Path, file: TargetPlatformConfigurationsFile) {
        Files.createDirectories(path.parent)
        Files.writeString(path, json.encodeToString(TargetPlatformConfigurationsFile.serializer(), file.normalized()))
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
        val modifiedMillis: Long,
    ) : TargetPlatformConfigurationLoadResult

    data class Missing(override val path: Path) : TargetPlatformConfigurationLoadResult

    data class Malformed(
        override val path: Path,
        val message: String,
    ) : TargetPlatformConfigurationLoadResult
}
