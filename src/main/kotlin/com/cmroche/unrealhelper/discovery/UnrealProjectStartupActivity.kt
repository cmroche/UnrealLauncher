package com.cmroche.unrealhelper.discovery

import com.cmroche.unrealhelper.settings.UnrealHelperSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class UnrealProjectStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = project.service<UnrealHelperSettings>()
        if (shouldRefreshProjectOnStartup(settings)) {
            project.service<UnrealProjectDiscoveryService>().refresh()
        }
    }
}

internal fun shouldRefreshProjectOnStartup(settings: UnrealHelperSettings): Boolean =
    settings.state.discoveryVersion < UnrealHelperSettings.CurrentDiscoveryVersion ||
        !settings.hasConfiguredProject() ||
        settings.state.engineRoot.isBlank()
