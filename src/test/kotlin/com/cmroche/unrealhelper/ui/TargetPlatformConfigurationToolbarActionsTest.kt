package com.cmroche.unrealhelper.ui

import com.cmroche.unrealhelper.config.TargetPlatformConfiguration
import com.cmroche.unrealhelper.config.TargetPlatformConfigurationLoadResult
import com.cmroche.unrealhelper.config.TargetPlatformConfigurationsFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class TargetPlatformConfigurationToolbarActionsTest {
    @Test
    fun `unselected toolbar presentation uses default label`() {
        val presentation = targetPlatformConfigurationPresentation("")

        assertEquals("Target & Platform", presentation.text)
        assertNull(presentation.description)
    }

    @Test
    fun `selected toolbar presentation uses configuration name`() {
        val presentation = targetPlatformConfigurationPresentation("Client and Server")

        assertEquals("Target & Platform: Client and Server", presentation.text)
        assertEquals("Target & Platform: Client and Server", presentation.description)
    }

    @Test
    fun `stale selected name is hidden from loaded presentation`() {
        val selectedName = targetPlatformConfigurationNameForPresentation(
            loadResult = TargetPlatformConfigurationLoadResult.Loaded(
                path = Path.of(".unrealhelper", "target-platforms.json"),
                file = TargetPlatformConfigurationsFile(
                    configurations = listOf(TargetPlatformConfiguration("Client and Server")),
                ),
                modifiedMillis = 1L,
            ),
            selectedName = "Removed",
        )

        assertEquals("", selectedName)
    }

    @Test
    fun `missing configurations require setup`() {
        val loadResult = TargetPlatformConfigurationLoadResult.Missing(
            Path.of(".unrealhelper", "target-platforms.json"),
        )

        assertTrue(targetPlatformConfigurationNeedsSetup(loadResult))
        assertEquals(
            TargetPlatformConfigurationPresentation(
                text = "Configure Targets",
                description = "Configure Target & Platform configurations",
            ),
            targetPlatformConfigurationPresentation(loadResult, selectedName = ""),
        )
        assertTrue(targetPlatformConfigurationUsesSetupStyle("Configure Targets"))
        assertFalse(targetPlatformConfigurationUsesSetupStyle("Target & Platform: Client"))
    }

    @Test
    fun `empty configuration file requires setup`() {
        assertTrue(
            targetPlatformConfigurationNeedsSetup(
                TargetPlatformConfigurationLoadResult.Loaded(
                    path = Path.of(".unrealhelper", "target-platforms.json"),
                    file = TargetPlatformConfigurationsFile(),
                    modifiedMillis = 1L,
                ),
            ),
        )
    }

    @Test
    fun `configuration file with an entry uses selector`() {
        assertFalse(
            targetPlatformConfigurationNeedsSetup(
                TargetPlatformConfigurationLoadResult.Loaded(
                    path = Path.of(".unrealhelper", "target-platforms.json"),
                    file = TargetPlatformConfigurationsFile(
                        configurations = listOf(TargetPlatformConfiguration("Client and Server")),
                    ),
                    modifiedMillis = 1L,
                ),
            ),
        )
    }

    @Test
    fun `malformed configuration does not enter setup state`() {
        assertFalse(
            targetPlatformConfigurationNeedsSetup(
                TargetPlatformConfigurationLoadResult.Malformed(
                    path = Path.of(".unrealhelper", "target-platforms.json"),
                    message = "Unexpected JSON",
                ),
            ),
        )
    }

    @Test
    fun `management load error reports malformed configuration`() {
        val error = targetPlatformConfigurationManagementError(
            TargetPlatformConfigurationLoadResult.Malformed(
                path = Path.of("/Project/.unrealhelper/target-platforms.json"),
                message = "Unexpected JSON",
            ),
        )

        assertEquals(
            "Could not open Target & Platform configurations from /Project/.unrealhelper/target-platforms.json: Unexpected JSON",
            error,
        )
    }

    @Test
    fun `management load error is absent for missing file`() {
        assertNull(
            targetPlatformConfigurationManagementError(
                TargetPlatformConfigurationLoadResult.Missing(Path.of("/Project/.unrealhelper/target-platforms.json")),
            ),
        )
    }
}
