package com.cmroche.unrealhelper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ToolbarActionIconsTest {
    @Test
    fun `workflow actions declare toolbar icons`() {
        val pluginXml = File("src/main/resources/META-INF/plugin.xml")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(pluginXml)

        assertEquals("com.intellij.icons.AllIcons.Actions.Compile", actionIcon(document, "UnrealHelper.BuildAction"))
        assertEquals("/icons/cook.svg", actionIcon(document, "UnrealHelper.CookAction"))
        assertEquals("/icons/package.svg", actionIcon(document, "UnrealHelper.PackageAction"))
        assertEquals("com.intellij.icons.AllIcons.Actions.Execute", actionIcon(document, "UnrealHelper.LaunchAction"))
        assertEquals("com.intellij.icons.AllIcons.Actions.Suspend", actionIcon(document, "UnrealHelper.StopLaunchAction"))
    }

    @Test
    fun `custom cook and package icons are bundled resources`() {
        assertNotNull(javaClass.getResource("/icons/cook.svg"))
        assertNotNull(javaClass.getResource("/icons/package.svg"))
    }

    @Test
    fun `package icon uses active blue palette`() {
        val packageIcon = requireNotNull(javaClass.getResource("/icons/package.svg")).readText()

        assertTrue(packageIcon.contains("#3574F0"))
    }

    @Test
    fun `target platform configuration toolbar actions are registered`() {
        val pluginXml = File("src/main/resources/META-INF/plugin.xml")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(pluginXml)

        assertEquals(
            "com.cmroche.unrealhelper.ui.TargetPlatformConfigurationSelectorAction",
            actionClass(document, "UnrealHelper.TargetPlatformConfigurationSelectorAction"),
        )
        assertEquals(
            "com.cmroche.unrealhelper.ui.TargetPlatformConfigurationManageAction",
            actionClass(document, "UnrealHelper.ManageTargetPlatformConfigurationsAction"),
        )
        assertEquals(
            "com.intellij.icons.AllIcons.General.Settings",
            actionIcon(document, "UnrealHelper.ManageTargetPlatformConfigurationsAction"),
        )
        assertEquals(
            "Configure ...",
            actionText(document, "UnrealHelper.ManageTargetPlatformConfigurationsAction"),
        )
        assertNull(actionClass(document, "UnrealHelper.TargetTypesToolbarAction"))
        assertNull(actionClass(document, "UnrealHelper.PlatformsToolbarAction"))
    }

    private fun actionClass(document: org.w3c.dom.Document, id: String): String? {
        val actions = document.getElementsByTagName("action")
        for (index in 0 until actions.length) {
            val action = actions.item(index) as org.w3c.dom.Element
            if (action.getAttribute("id") == id) {
                return action.getAttribute("class").takeIf { it.isNotBlank() }
            }
        }

        return null
    }

    private fun actionIcon(document: org.w3c.dom.Document, id: String): String? {
        val actions = document.getElementsByTagName("action")
        for (index in 0 until actions.length) {
            val action = actions.item(index) as org.w3c.dom.Element
            if (action.getAttribute("id") == id) {
                return action.getAttribute("icon").takeIf { it.isNotBlank() }
            }
        }

        return null
    }

    private fun actionText(document: org.w3c.dom.Document, id: String): String? {
        val actions = document.getElementsByTagName("action")
        for (index in 0 until actions.length) {
            val action = actions.item(index) as org.w3c.dom.Element
            if (action.getAttribute("id") == id) {
                return action.getAttribute("text").takeIf { it.isNotBlank() }
            }
        }

        return null
    }
}
