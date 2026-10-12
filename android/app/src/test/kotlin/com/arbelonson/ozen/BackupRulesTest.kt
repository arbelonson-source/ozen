package com.arbelonson.ozen

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Document
import org.w3c.dom.Element

class BackupRulesTest {
    private fun parse(path: String): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))

    @Test
    fun `a move to a new phone leaves the marked problems' sound and the journal behind, and nothing goes to a cloud backup`() {
        val application = parse("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("false", application.getAttribute("android:allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.getAttribute("android:dataExtractionRules"))

        val rules = File("src/main/res/xml/data_extraction_rules.xml")
        assertTrue(rules.isFile, "no rules for a move to a new phone")
        val transfer = parse(rules.path).getElementsByTagName("device-transfer").item(0) as Element
        val excludes = transfer.getElementsByTagName("exclude")
        val left = (0 until excludes.length).map { excludes.item(it) as Element }.map { it.getAttribute("domain") to it.getAttribute("path") }.toSet()
        assertEquals(setOf("file" to "${CaptionSession.PROBLEM_AUDIO_FOLDER}/", "file" to CaptionSession.JOURNAL_FILE), left)
    }
}
