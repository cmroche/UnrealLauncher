package com.cmroche.unrealhelper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionToolbarActionsTest {
    @Test
    fun `empty selection uses bare toolbar label`() {
        val presentation = selectionToolbarPresentation(
            emptyText = "Targets",
            labelPrefix = "Targets",
            selectedValues = emptyList(),
        )

        assertEquals("Targets", presentation.text)
        assertNull(presentation.description)
    }

    @Test
    fun `single selection includes selected value in toolbar label and description`() {
        val presentation = selectionToolbarPresentation(
            emptyText = "Targets",
            labelPrefix = "Targets",
            selectedValues = listOf("Game"),
        )

        assertEquals("Targets: Game", presentation.text)
        assertEquals("Targets: Game", presentation.description)
    }

    @Test
    fun `multiple selections keep toolbar label compact and description complete`() {
        val presentation = selectionToolbarPresentation(
            emptyText = "Platforms",
            labelPrefix = "Platforms",
            selectedValues = listOf("Win64", "Mac", "Linux", "Android"),
        )

        assertEquals("Platforms: Win64 +3", presentation.text)
        assertEquals("Platforms: Win64, Mac, Linux, Android", presentation.description)
    }
}
