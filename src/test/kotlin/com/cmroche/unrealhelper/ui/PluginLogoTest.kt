package com.cmroche.unrealhelper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class PluginLogoTest {
    @Test
    fun `light and dark plugin logos are bundled at marketplace size`() {
        listOf("pluginIcon.svg", "pluginIcon_dark.svg").forEach { filename ->
            val resource = javaClass.getResource("/META-INF/$filename")
            assertNotNull(resource)

            val document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(requireNotNull(resource).openStream())
            val svg = document.documentElement

            assertEquals("svg", svg.tagName)
            assertEquals("40", svg.getAttribute("width"))
            assertEquals("40", svg.getAttribute("height"))
            assertEquals("0 0 40 40", svg.getAttribute("viewBox"))
        }
    }

    @Test
    fun `plugin logos remain compact vector artwork`() {
        listOf("pluginIcon.svg", "pluginIcon_dark.svg").forEach { filename ->
            val content = requireNotNull(javaClass.getResource("/META-INF/$filename")).readText()

            assertTrue("$filename should remain below 3 KiB", content.toByteArray().size < 3 * 1024)
            assertTrue("$filename should not embed raster artwork", "<image" !in content)
            assertTrue("$filename should not contain visible text", "<text" !in content)
            assertTrue("$filename should not use gradients", "Gradient" !in content)
        }
    }
}
