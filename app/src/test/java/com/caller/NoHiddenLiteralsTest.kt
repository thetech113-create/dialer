package com.caller

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Play Protect investigation 2026-11-22: helpline and bank numbers were written as Base64 strings decoded at run time
 * (MyInCallService, P2PCallerIdUtils, SmsMessaging), inside the code that labels callers and sends caller-ID SMS.
 * Nothing in them is secret; hiding phone numbers in SMS code only looks like malware to a scanner. They are plain
 * text now, and no source file may decode a hard-coded Base64 string again.
 */
class NoHiddenLiteralsTest {
    @Test
    fun noSourceDecodesAHardCodedBase64String() {
        val root = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }
        val pattern = Regex("""Base64\.decode\(\s*"""")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { pattern.containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()
        assertTrue("files decoding hard-coded Base64 strings: $offenders", offenders.isEmpty())
    }
}
