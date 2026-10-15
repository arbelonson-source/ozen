package com.arbelonson.ozen

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class ManifestQueriesTest {
    @Test
    fun `manifest asks to see text to speech engines`() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val queries = doc.documentElement.getElementsByTagName("queries")
        val actions = (0 until queries.length).flatMap { i ->
            val actionNodes = (queries.item(i) as org.w3c.dom.Element).getElementsByTagName("action")
            (0 until actionNodes.length).map { (actionNodes.item(it) as org.w3c.dom.Element).getAttribute("android:name") }
        }
        assertTrue("android.intent.action.TTS_SERVICE" in actions, "queries lists $actions")
    }
}
