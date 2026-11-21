package com.arbelonson.ozen

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertTrue

class ManifestOpenWithTest {
    private fun Element.names(tag: String, attribute: String) =
        (0 until getElementsByTagName(tag).length).map { (getElementsByTagName(tag).item(it) as Element).getAttribute(attribute) }

    private fun filters(): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val nodes = doc.documentElement.getElementsByTagName("intent-filter")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun aRecordingFromAFilesAppCanBeOpenedWithOzen() {
        val open = filters().filter { "android.intent.action.VIEW" in it.names("action", "android:name") }
            .filter { "audio/*" in it.names("data", "android:mimeType") }
        assertTrue(open.isNotEmpty(), "no VIEW filter for audio")
        open.forEach {
            assertTrue("video/*" in it.names("data", "android:mimeType"))
            assertTrue("android.intent.category.DEFAULT" in it.names("category", "android:name"))
            val schemes = it.names("data", "android:scheme").filter(String::isNotEmpty)
            assertTrue(schemes == listOf("content"), "schemes were $schemes")
        }
    }
}
