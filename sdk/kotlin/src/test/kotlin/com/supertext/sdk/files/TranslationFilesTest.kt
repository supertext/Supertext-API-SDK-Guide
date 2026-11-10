package com.supertext.sdk.files

import com.supertext.sdk.v1.Spec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Every case in spec/translation-file.json. */
class TranslationFilesTest {

    private val spec = Spec.load("translation-file.json")

    private fun segment(o: Any?): FileSegment {
        val s = Spec.obj(o)
        return FileSegment(s["key"] as String? ?: "", s["text"] as String, s["html"] == true)
    }

    private fun segments(o: Any?): List<FileSegment> = (o as List<*>).map(::segment)

    private fun job(o: Any?): FileJob {
        val j = Spec.obj(o)
        return FileJob(
            j["plugin"] as String, j["reference"] as String, j["sourceLanguage"] as String,
            j["targetLanguage"] as String, segments(j["segments"]),
        )
    }

    private fun jobs(o: Any?): List<FileJob> = (o as List<*>).map(::job)

    private fun expectError(c: Map<String, Any?>, call: () -> Any) {
        val e = assertThrows(TranslationFileException::class.java) { call() }
        assertEquals(c["error"], e.code.value)
    }

    /** A file as the spec writes it (camelCase JSON). */
    private fun describe(f: TranslationFile): Map<String, Any?> = linkedMapOf(
        "format" to f.format.value,
        "plugin" to f.plugin,
        "reference" to f.reference,
        "sourceLanguage" to f.sourceLanguage,
        "targetLanguage" to f.targetLanguage,
        "units" to f.units.map { linkedMapOf("key" to it.key, "checksum" to it.checksum) },
    )

    private fun describe(s: ImportedSegment): Map<String, Any?> =
        linkedMapOf("key" to s.key, "status" to s.status.value, "text" to s.text)

    @TestFactory
    fun checksum(): List<DynamicTest> = Spec.list(spec, "checksum").map { c ->
        dynamicTest("checksum ${c["segment"]}") { assertEquals(c["out"], TranslationFiles.checksum(segment(c["segment"]))) }
    }

    @TestFactory
    fun exportXliff(): List<DynamicTest> = Spec.list(spec, "exportXliff").map { c ->
        dynamicTest("exportXliff: ${c["name"]}") {
            if ("error" in c) {
                expectError(c) { TranslationFiles.exportXliff(jobs(c["jobs"])) }
            } else {
                assertEquals(c["out"], TranslationFiles.exportXliff(jobs(c["jobs"])))
            }
        }
    }

    @TestFactory
    fun exportHtml(): List<DynamicTest> = Spec.list(spec, "exportHtml").map { c ->
        dynamicTest("exportHtml: ${c["name"]}") {
            if ("error" in c) {
                expectError(c) { TranslationFiles.exportHtml(job(c["job"])) }
            } else {
                assertEquals(c["out"], TranslationFiles.exportHtml(job(c["job"])))
            }
        }
    }

    @TestFactory
    fun read(): List<DynamicTest> = Spec.list(spec, "read").map { c ->
        dynamicTest("read: ${c["name"]}") {
            val content = c["repeat"]?.let {
                val repeat = Spec.obj(it)
                (repeat["text"] as String).repeat(Spec.int(repeat["times"]))
            } ?: c["content"] as String
            if ("error" in c) {
                expectError(c) { TranslationFiles.read(content) }
            } else {
                assertEquals(c["files"], TranslationFiles.read(content).map(::describe))
            }
        }
    }

    @TestFactory
    fun importTranslations(): List<DynamicTest> = Spec.list(spec, "import").map { c ->
        dynamicTest("import: ${c["name"]}") {
            val index = c["file"]?.let(Spec::int) ?: 0
            val file = TranslationFiles.read(c["content"] as String)[index]
            assertEquals(c["out"], TranslationFiles.importTranslations(file, segments(c["current"])).map(::describe))
        }
    }

    @Test
    fun aFileNotReturnedByReadIsRefused() {
        val file = TranslationFile(FileFormat.HTML, "P", "r", "de", "fr", emptyList())
        val e = assertThrows(TranslationFileException::class.java) { TranslationFiles.importTranslations(file, emptyList()) }
        assertEquals(TranslationFileErrorCode.INVALID_FILE, e.code)
    }

    @Test
    fun aDoctypeIsNeverExpanded() {
        val xliff = "<?xml version=\"1.0\"?>\n<!DOCTYPE xliff [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;\">]>\n" +
            "<xliff version=\"1.2\"><file original=\"r\" source-language=\"de\" target-language=\"fr\" product-name=\"P\">" +
            "<body><trans-unit id=\"k\"><source>x</source><target>&b;</target></trans-unit></body></file></xliff>"
        val e = assertThrows(TranslationFileException::class.java) { TranslationFiles.read(xliff) }
        assertEquals(TranslationFileErrorCode.INVALID_FILE, e.code)
    }

    @Test
    fun roundTripThroughHtml() {
        val job = FileJob(
            "P", "page:1", "de", "fr",
            listOf(FileSegment("title", "Hallo"), FileSegment("body", "<p>Ein <b>Test</b></p>", true)),
        )
        val file = TranslationFiles.read(TranslationFiles.exportHtml(job))[0]
        assertEquals(
            listOf(ImportedSegment("title", ImportStatus.OK, "Hallo"), ImportedSegment("body", ImportStatus.OK, "<p>Ein <b>Test</b></p>")),
            TranslationFiles.importTranslations(file, job.segments),
        )
    }

    @Test
    fun deeplyNestedTargetsDontOverflowTheStack() {
        val depth = 100_000
        val xliff = "<xliff version=\"1.2\"><file original=\"r\" source-language=\"de\" target-language=\"fr\" product-name=\"P\">" +
            "<body><trans-unit id=\"k\" extradata=\"00000000\"><source>x</source><target>" + "<mrk mtype=\"x\">".repeat(depth) +
            "y" + "</mrk>".repeat(depth) + "</target></trans-unit></body></file></xliff>"
        val file = TranslationFiles.read(xliff)[0]
        val imported = TranslationFiles.importTranslations(file, listOf(FileSegment("k", "x")))
        assertEquals(ImportStatus.SOURCE_CHANGED, imported[0].status)
        assertEquals("y", imported[0].text)
    }
}
