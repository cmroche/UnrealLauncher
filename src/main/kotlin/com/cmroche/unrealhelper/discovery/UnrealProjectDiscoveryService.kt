package com.cmroche.unrealhelper.discovery

import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
class UnrealProjectDiscoveryService(private val project: Project) {
    fun refresh(): UnrealProjectDiscoveryResult {
        val basePath = project.basePath
        val result = if (basePath.isNullOrBlank()) {
            UnrealProjectDiscoveryResult(
                workspaceRoot = null,
                uprojectPath = null,
                targets = emptyList(),
                platforms = emptyList(),
                warnings = listOf("Project base path is unavailable."),
            )
        } else {
            UnrealProjectDiscovery.discover(Paths.get(basePath))
        }

        project.service<UnrealHelperSettings>().applyDiscoveryResult(result)
        return result
    }
}
