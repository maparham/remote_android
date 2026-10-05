package com.example.lanremote.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ApkDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val data = ByteArray(300_000) { (it % 251).toByte() }

    private fun sourceUrl(bytes: ByteArray): String {
        val f = tmp.newFile("src.bin")
        f.writeBytes(bytes)
        return f.toURI().toURL().toString()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun downloadsVerifiesAndReportsProgress() = runBlocking<Unit> {
        val dir = tmp.newFolder("updates")
        val progress = mutableListOf<Int>()
        val out = ApkDownloader.download(sourceUrl(data), dir, "app.apk", sha256(data)) { progress += it }
        assertEquals(File(dir, "app.apk"), out)
        assertArrayEquals(data, out.readBytes())
        assertEquals(100, progress.last())
        assertTrue(progress.first() < 100)
        assertEquals(progress, progress.sorted())
    }

    @Test fun skipsVerificationWhenNoDigest() = runBlocking<Unit> {
        val dir = tmp.newFolder("updates")
        val out = ApkDownloader.download(sourceUrl(data), dir, "app.apk", null)
        assertArrayEquals(data, out.readBytes())
    }

    @Test fun acceptsUpperCaseDigest() = runBlocking<Unit> {
        val dir = tmp.newFolder("updates")
        ApkDownloader.download(sourceUrl(data), dir, "app.apk", sha256(data).uppercase())
        assertTrue(File(dir, "app.apk").exists())
    }

    @Test fun rejectsWrongDigestAndDeletesFile() = runBlocking<Unit> {
        val dir = tmp.newFolder("updates")
        try {
            ApkDownloader.download(sourceUrl(data), dir, "app.apk", "00".repeat(32))
            fail("expected DigestMismatchException")
        } catch (e: DigestMismatchException) {
            assertTrue(e.message!!.contains("SHA-256"))
        }
        assertFalse(File(dir, "app.apk").exists())
    }

    @Test fun clearsStaleFiles() = runBlocking<Unit> {
        val dir = tmp.newFolder("updates")
        File(dir, "old-partial.apk").writeText("junk")
        ApkDownloader.download(sourceUrl(data), dir, "app.apk", null)
        assertFalse(File(dir, "old-partial.apk").exists())
        assertEquals(listOf("app.apk"), dir.list()!!.toList())
    }

    @Test fun createsDirectoryIfMissing() = runBlocking<Unit> {
        val dir = File(tmp.root, "missing/updates")
        val out = ApkDownloader.download(sourceUrl(data), dir, "app.apk", null)
        assertTrue(out.exists())
    }

    @Test fun failedDownloadLeavesNoPartialFile() = runBlocking<Unit> {
        val dir = tmp.newFolder("updates")
        try {
            ApkDownloader.download(sourceUrl(data), dir, "app.apk", null) {
                throw java.io.IOException("connection reset") // fails mid-stream
            }
            fail("expected IOException")
        } catch (e: java.io.IOException) {
            assertEquals("connection reset", e.message)
        }
        assertFalse(File(dir, "app.apk").exists())
    }
}
