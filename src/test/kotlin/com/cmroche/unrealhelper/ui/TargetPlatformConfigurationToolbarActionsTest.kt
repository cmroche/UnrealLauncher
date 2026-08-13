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
    fun `configuration popup uses concise configure label`() {
        assertEquals("Configure ...", TargetPlatformConfigurationManageAction().templatePresentation.text)
    }

    @Test
    fun `unselected selector state uses default label`() {
        val state = targetPlatformConfigurationSelectorState(loaded("Client and Server"), "")

        assertEquals(TargetPlatformConfigurationSelectorState.Unselected, state)
        assertEquals("Configure ...", state.text)
        assertEquals("Select Target & Platform configuration", state.description)
        assertTrue(state.isEnabled)
        assertTrue(state.usesSetupStyle)
    }

    @Test
    fun `selected selector state uses configuration name`() {
        val state = targetPlatformConfigurationSelectorState(loaded("Client and Server"), "Client and Server")

        assertEquals(TargetPlatformConfigurationSelectorState.Selected("Client and Server"), state)
        assertEquals("Client and Server", state.text)
        assertEquals(
            "Selected Target & Platform configuration: Client and Server",
            state.description,
        )
        assertTrue(state.isEnabled)
        assertFalse(state.usesSetupStyle)
    }

    @Test
    fun `stale selected name is hidden from loaded presentation`() {
        val state = targetPlatformConfigurationSelectorState(
            loadResult = loaded("Client and Server"),
            selectedName = "Removed",
        )

        assertEquals(TargetPlatformConfigurationSelectorState.Unselected, state)
    }

    @Test
    fun `missing configurations require setup`() {
        val loadResult = TargetPlatformConfigurationLoadResult.Missing(
            Path.of(".unrealhelper", "target-platforms.json"),
        )

        assertTrue(targetPlatformConfigurationNeedsSetup(loadResult))
        val state = targetPlatformConfigurationSelectorState(loadResult, selectedName = "")
        assertEquals(TargetPlatformConfigurationSelectorState.Setup, state)
        assertTrue(state.isEnabled)
        assertTrue(state.usesSetupStyle)
        assertTrue(state.opensManagement)
    }

    @Test
    fun `configuration named like the default label does not use setup style`() {
        val state = targetPlatformConfigurationSelectorState(loaded("Configure ..."), "Configure ...")

        assertEquals(TargetPlatformConfigurationSelectorState.Selected("Configure ..."), state)
        assertFalse(state.usesSetupStyle)
    }

    @Test
    fun `empty configuration file requires setup`() {
        val state = targetPlatformConfigurationSelectorState(
            TargetPlatformConfigurationLoadResult.Loaded(
                path = Path.of(".unrealhelper", "target-platforms.json"),
                file = TargetPlatformConfigurationsFile(),
            ),
            selectedName = "",
        )

        assertEquals(TargetPlatformConfigurationSelectorState.Setup, state)
    }

    @Test
    fun `configuration file with an entry uses selector`() {
        val state = targetPlatformConfigurationSelectorState(
            loaded("Client and Server"),
            selectedName = "Client and Server",
        )

        assertEquals(TargetPlatformConfigurationSelectorState.Selected("Client and Server"), state)
    }

    @Test
    fun `malformed configuration does not enter setup state`() {
        val loadResult = TargetPlatformConfigurationLoadResult.Malformed(
            path = Path.of(".unrealhelper", "target-platforms.json"),
            message = "Unexpected JSON",
        )

        assertFalse(targetPlatformConfigurationNeedsSetup(loadResult))
        val state = targetPlatformConfigurationSelectorState(loadResult, selectedName = "Client")
        assertEquals(TargetPlatformConfigurationSelectorState.Unavailable, state)
        assertFalse(state.isEnabled)
        assertTrue(state.usesSetupStyle)
        assertFalse(state.opensManagement)
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

    private fun loaded(vararg names: String): TargetPlatformConfigurationLoadResult.Loaded =
        TargetPlatformConfigurationLoadResult.Loaded(
            path = Path.of(".unrealhelper", "target-platforms.json"),
            file = TargetPlatformConfigurationsFile(
                configurations = names.map(::TargetPlatformConfiguration),
            ),
        )
}
