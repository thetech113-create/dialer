package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Play Protect investigation 2026-11-22 (Lint DataExtractionRules): for apps targeting Android 12+, allowBackup="false"
 * is ignored when data moves to a new phone (compat change IGNORE_ALLOW_BACKUP_IN_D2D), so the device identity keys,
 * PC pairing keys, server login and cached call history would be copied to it. The data extraction rules now keep
 * every app storage area out of both cloud backup and device-to-device transfer, as allowBackup="false" intended.
 */
class BackupRulesTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val domains = setOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref"
    )

    private fun parse(vararg candidates: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(candidates.map(::File).first { it.exists() })

    @Test
    fun theManifestPointsAtTheRules() {
        val app = parse("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(androidNs, "dataExtractionRules"))
        assertEquals("false", app.getAttributeNS(androidNs, "allowBackup"))
    }

    @Test
    fun everyStorageAreaIsExcludedFromCloudAndDeviceTransfer() {
        val rules = parse("src/main/res/xml/data_extraction_rules.xml", "app/src/main/res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val node = rules.getElementsByTagName(section).item(0) as Element?
            assertTrue("missing <$section>", node != null)
            val excludes = node!!.getElementsByTagName("exclude")
            val excluded = (0 until excludes.length).map { excludes.item(it) as Element }
                .filter { it.getAttribute("path") == "." }
                .map { it.getAttribute("domain") }
                .toSet()
            assertEquals("<$section> must exclude every domain", domains, excluded)
            assertEquals("<$section> must include nothing", 0, node.getElementsByTagName("include").length)
        }
    }
}
