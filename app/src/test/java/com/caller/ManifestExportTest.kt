package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ManifestExportTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun components(): List<Element> {
        val file = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(file)
        return listOf("activity", "receiver", "service", "provider").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
    }

    private fun Element.attr(name: String) = getAttributeNS(androidNs, name)

    private fun Element.actions(): List<String> {
        val nodes = getElementsByTagName("action")
        return (0 until nodes.length).map { (nodes.item(it) as Element).attr("name") }
    }

    @Test
    fun theCallControlReceiverIsNotOpenToOtherApps() {
        // Any app could send answer / reject / hang-up broadcasts to it while it was exported.
        val receiver = components().first { it.attr("name") == ".NotificationRepostReceiver" }
        assertEquals("false", receiver.attr("exported"))
    }

    @Test
    fun noOpenComponentAcceptsTheAppsOwnActions() {
        // com.caller.* actions are the app's own buttons (PendingIntents): they never need to be reachable by other apps.
        val open = components().filter { it.attr("exported") == "true" }
        val offenders = open.filter { c -> c.actions().any { it.startsWith("com.caller.") } }.map { it.attr("name") }
        assertTrue("open components with the app's own actions: $offenders", offenders.isEmpty())
    }

    @Test
    fun theManifestWasFound() {
        assertFalse(components().isEmpty())
    }
}
