package com.arbelonson.ozen.core

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val threadMemory = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

private class FreshCore(parent: ClassLoader) : ClassLoader(parent) {
    val readBytes = AtomicLong()
    private val mainClasses = TranslationTable::class.java.protectionDomain.codeSource.location.toString()

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            val path = name.replace('.', '/') + ".class"
            val inMain = parent.getResource(path)?.toString()?.startsWith(mainClasses) == true
            if (!inMain) return super.loadClass(name, resolve)
            val bytes = parent.getResourceAsStream(path)!!.use { it.readBytes() }
            return defineClass(name, bytes, 0, bytes.size)
        }
    }

    override fun getResourceAsStream(name: String): InputStream? = parent.getResourceAsStream(name)?.let { stream ->
        object : FilterInputStream(stream) {
            override fun read(): Int = super.read().also { if (it >= 0) readBytes.incrementAndGet() }

            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) readBytes.addAndGet(it.toLong()) }
        }
    }
}

private class FirstLookup(val text: String?, val readBytes: Long, val allocatedBytes: Long, val cpuMillis: Double)

private fun firstLookup(english: String, language: UILanguage): FirstLookup {
    val core = FreshCore(TranslationTableCostTest::class.java.classLoader)
    val table = core.loadClass(TranslationTable::class.java.name)
    val languages = core.loadClass(UILanguage::class.java.name)
    val asked = languages.enumConstants.single { (it as Enum<*>).name == language.name }
    val lookup = table.getMethod("lookup", String::class.java, languages)
    val instance = table.getField("INSTANCE").get(null)
    val allocatedBefore = threadMemory.currentThreadAllocatedBytes
    val cpuBefore = threadMemory.currentThreadCpuTime
    val text = lookup.invoke(instance, english, asked) as String?
    val cpu = (threadMemory.currentThreadCpuTime - cpuBefore) / 1_000_000.0
    return FirstLookup(text, core.readBytes.get(), threadMemory.currentThreadAllocatedBytes - allocatedBefore, cpu)
}

class TranslationTableCostTest {
    @Test
    fun `the first lookup in a language reads and builds only that language`() {
        assertTrue(threadMemory.isThreadAllocatedMemorySupported && threadMemory.isThreadAllocatedMemoryEnabled)
        val cold = firstLookup("Start captions", UILanguage.French)
        println("first lookup in a new process: read ${cold.readBytes / 1_024} KB, allocated ${cold.allocatedBytes / 1_024} KB, ${"%.1f".format(cold.cpuMillis)} ms of CPU")
        val translated = UILanguage.entries.filter { it != UILanguage.Hebrew && it != UILanguage.English }
        val root = File(System.getProperty("ozen.fixtures")).parentFile.parentFile
        val whole = File(root, "Sources/OzenKit/Resources/Translations.json").length()
        val readAllowed = whole / 4
        val allocatedAllowed = whole * 2
        val failures = ArrayList<String>()
        for (language in translated) {
            val first = firstLookup("Start captions", language)
            println("first $language lookup: read ${first.readBytes / 1_024} KB, allocated ${first.allocatedBytes / 1_024} KB, ${"%.1f".format(first.cpuMillis)} ms of CPU")
            assertEquals(TranslationTable.lookup("Start captions", language), first.text, "$language")
            if (first.readBytes > readAllowed) failures += "$language read ${first.readBytes / 1_024} KB"
            if (first.allocatedBytes > allocatedAllowed) failures += "$language allocated ${first.allocatedBytes / 1_024} KB"
        }
        val limits = "at most ${readAllowed / 1_024} KB read and ${allocatedAllowed / 1_024} KB allocated, the table of all languages being ${whole / 1_024} KB"
        assertTrue(failures.isEmpty(), "the first lookup in a language cost more than one language's table ($limits): $failures")
    }
}
