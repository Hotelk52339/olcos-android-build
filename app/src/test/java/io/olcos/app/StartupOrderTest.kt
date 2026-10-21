package io.olcos.app

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The order AppGraph.init makes its stores in. AppSettings resolves every
 * protocol's room rule once, as it is made (RoomRules.effective), and an MTS
 * Link schedule is only allowed once the app can make MTS Link meetings
 * (RoomServices.MTSLink.canCreate: the learned template and the saved
 * sign-in). Both are read from the graph; made before them, the settings see
 * no graph, read every stored MTS Link schedule as Off and keep it so for
 * the whole process. No Application runs in a JVM test, so the order is read
 * from the source, as StringsParityTest reads the string files; where the
 * source cannot be found from the working directory, the check skips.
 */
class StartupOrderTest {
    private fun source(): String? =
        listOf("src/main/java/io/olcos/app/OlcosApp.kt", "app/src/main/java/io/olcos/app/OlcosApp.kt")
            .map(::File).firstOrNull { it.isFile }?.readText(Charsets.UTF_8)

    @Test fun theSettingsAreMadeAfterWhatTheMtsLinkRuleDependsOn() {
        val text = source()
        assumeTrue("OlcosApp.kt is not reachable from here", text != null)
        val init = text!!.substringAfter("fun init(")
        val settings = init.indexOf("settings = AppSettings(")
        val session = init.indexOf("mts = MTSLinkSession(")
        val template = init.indexOf("mtsTemplate = MTSLinkTemplate(")
        assertTrue("init makes no AppSettings", settings >= 0)
        assertTrue("init makes no MTSLinkSession", session >= 0)
        assertTrue("init makes no MTSLinkTemplate", template >= 0)
        assertTrue("AppSettings is made before the MTS Link sign-in it reads", settings > session)
        assertTrue("AppSettings is made before the MTS Link template it reads", settings > template)
    }
}
