package com.cmroche.unrealhelper.execution

import com.intellij.build.DefaultBuildDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UnrealWorkflowPresenterTest {
    @Test
    fun `build progress uses UE Launcher tab title`() {
        val descriptor = DefaultBuildDescriptor("id", "Workflow title", "/project", 1L)

        val progressDescriptor = unrealBuildProgressDescriptor(descriptor)

        assertEquals("UE Launcher", progressDescriptor.title)
        assertSame(descriptor, progressDescriptor.buildDescriptor)
    }
}
