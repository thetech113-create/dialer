package com.caller

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Play Protect investigation 2026-11-22: permissions and components that no code uses are not declared.
 * - REORDER_TASKS, DISABLE_KEYGUARD, MANAGE_OWN_CALLS, WRITE_EXTERNAL_STORAGE: no code needs them.
 * - MyConnectionService: the app registers no phone account for it (and removes its own), so Telecom never used it.
 * - OtpSmsReceiver: Google's SMS Retriever only delivers texts carrying this app's hash, which no text does; it was
 *   exported behind RECEIVE_SMS, so any app holding that permission could make it copy a fake "OTP" to the clipboard.
 */
class ManifestMinimisationTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val manifest by lazy {
        val file = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
    }

    private fun names(tag: String): List<String> {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }
    }

    @Test
    fun unusedPermissionsAreNotDeclared() {
        val declared = names("uses-permission")
        val unused = listOf(
            "android.permission.REORDER_TASKS",
            "android.permission.DISABLE_KEYGUARD",
            "android.permission.MANAGE_OWN_CALLS",
            "android.permission.WRITE_EXTERNAL_STORAGE"
        ).filter { it in declared }
        assertTrue("unused permissions still declared: $unused", unused.isEmpty())
    }

    @Test
    fun deadComponentsAreGone() {
        val components = names("service") + names("receiver")
        val dead = listOf(".MyConnectionService", ".OtpSmsReceiver").filter { it in components }
        assertTrue("dead components still declared: $dead", dead.isEmpty())
    }
}
