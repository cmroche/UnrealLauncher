package com.cmroche.unrealhelper.run

import com.cmroche.unrealhelper.settings.UnrealHelperSettingsState
import com.intellij.execution.configurations.ParametersList
import com.jetbrains.rider.run.configurations.TerminalMode
import com.jetbrains.rider.run.configurations.exe.ExeConfigurationParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class UnrealHelperCppConfigurationParametersExtensionTest {
    @Test
    fun `plugin registers Rider Cpp configuration parameters extension`() {
        val pluginXml = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/resources/META-INF/plugin.xml"))
        val extensionGroups = pluginXml.getElementsByTagName("extensions")

        val isRegistered = (0 until extensionGroups.length).any { index ->
            val group = extensionGroups.item(index) as org.w3c.dom.Element
            group.getAttribute("defaultExtensionNs") == "com.jetbrains.rider-cpp" &&
                group.getElementsByTagName("run.configurations.cpp").let { extensions ->
                    (0 until extensions.length).any { extensionIndex ->
                        (extensions.item(extensionIndex) as org.w3c.dom.Element).getAttribute("implementation") ==
                            "com.cmroche.unrealhelper.run.UnrealHelperCppConfigurationParametersExtension"
                    }
                }
        }

        assertTrue(isRegistered)
    }

    @Test
    fun `injects active arguments into Rider Uproject parameters`() {
        val state = enabledState()
        val parameters = parameters("\"/Project/Lyra/Lyra.uproject\" -game -log")

        assertTrue(injectGlobalArgsIntoRiderUproject(parameters, state))

        assertEquals(
            listOf("/Project/Lyra/Lyra.uproject", "-game", "-log", "-newconsole"),
            ParametersList.parse(parameters.programParameters).toList(),
        )
    }

    @Test
    fun `does not inject into a Rider Cpp launch without a Uproject argument`() {
        val parameters = parameters("--tool-mode")

        assertFalse(injectGlobalArgsIntoRiderUproject(parameters, enabledState()))
        assertEquals("--tool-mode", parameters.programParameters)
    }

    @Test
    fun `does not inject when Run Debug integration is disabled`() {
        val state = enabledState().also { it.applyToRunDebug = false }
        val parameters = parameters("\"/Project/Lyra/Lyra.uproject\"")

        assertFalse(injectGlobalArgsIntoRiderUproject(parameters, state))
        assertEquals("\"/Project/Lyra/Lyra.uproject\"", parameters.programParameters)
    }

    private fun enabledState(): UnrealHelperSettingsState = UnrealHelperSettingsState().also {
        it.uprojectPath = "/Project/Lyra/Lyra.uproject"
        it.activeCommandLine = "-game -log -newconsole"
        it.applyToRunDebug = true
    }

    private fun parameters(programParameters: String): ExeConfigurationParameters =
        ExeConfigurationParameters(
            exePath = "/Engine/Binaries/Mac/UnrealEditor",
            programParameters = programParameters,
            workingDirectory = "/Engine/Binaries/Mac",
            envs = emptyMap(),
            isPassParentEnvs = true,
            terminalMode = TerminalMode.Auto,
            envFilePaths = emptyList(),
            redirectInputPath = "",
            mixedModeDebugging = false,
        )
}
