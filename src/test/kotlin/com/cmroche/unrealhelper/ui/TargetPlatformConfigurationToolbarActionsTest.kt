package com.cmroche.unrealhelper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
