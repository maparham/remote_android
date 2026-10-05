package com.example.lanremote.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UpdateCheckerTest {
    private val sample = """
        {"tag_name":"v0.2","prerelease":false,"assets":[
          {"name":"notes.txt","size":10,"digest":null,"browser_download_url":"https://x/notes.txt"},
          {"name":"lan-remote-0.2.apk","size":16289599,"digest":"sha256:2745F2E3",
           "browser_download_url":"https://x/lan-remote-0.2.apk"}
        ]}
    """.trimIndent()

    @Test fun parsesReleaseAndPicksApkAsset() {
        val r = UpdateChecker.parseRelease(sample)!!
        assertEquals("0.2", r.versionName)
        assertEquals("lan-remote-0.2.apk", r.apkName)
        assertEquals("https://x/lan-remote-0.2.apk", r.apkUrl)
        assertEquals(16289599L, r.sizeBytes)
        assertEquals("2745f2e3", r.sha256) // lower-cased, prefix stripped
    }

    @Test fun missingDigestGivesNullSha() {
        val body = """{"tag_name":"v0.2","prerelease":false,"assets":[
            {"name":"a.apk","size":1,"browser_download_url":"https://x/a.apk"}]}"""
        assertNull(UpdateChecker.parseRelease(body)!!.sha256)
    }

    @Test fun prereleaseIsIgnored() {
        assertNull(UpdateChecker.parseRelease(sample.replace("\"prerelease\":false", "\"prerelease\":true")))
    }

    @Test fun noApkAssetIsIgnored() {
        val body = """{"tag_name":"v0.2","prerelease":false,"assets":[
            {"name":"notes.txt","size":1,"browser_download_url":"https://x/n"}]}"""
        assertNull(UpdateChecker.parseRelease(body))
    }

    @Test fun nonNumericTagIsIgnored() {
        assertNull(UpdateChecker.parseRelease(sample.replace("v0.2", "v0.2-beta")))
        assertNull(UpdateChecker.parseRelease(sample.replace("v0.2", "v1.0rc1")))
    }

    @Test fun garbageIsIgnored() {
        assertNull(UpdateChecker.parseRelease("not json"))
        assertNull(UpdateChecker.parseRelease("[]"))
        assertNull(UpdateChecker.parseRelease("""{"assets":[]}"""))
    }

    @Test fun comparesSegmentsNumerically() {
        assertTrue(UpdateChecker.compareVersions("0.10", "0.9") > 0)
        assertTrue(UpdateChecker.compareVersions("0.9", "0.10") < 0)
        assertEquals(0, UpdateChecker.compareVersions("1.0", "1.0.0"))
        assertTrue(UpdateChecker.compareVersions("1.0.1", "1.0") > 0)
        assertEquals(0, UpdateChecker.compareVersions("1.x", "1.0"))
    }

    @Test fun isNewerOnlyForStrictlyGreater() {
        val r = UpdateChecker.parseRelease(sample)!!
        assertTrue(UpdateChecker.isNewer(r, "0.1"))
        assertFalse(UpdateChecker.isNewer(r, "0.2"))
        assertFalse(UpdateChecker.isNewer(r, "0.3"))
    }

    @Test fun fetchLatestReturnsNullOnFailure() = runBlocking<Unit> {
        val c = UpdateChecker(httpGet = { throw IOException("HTTP 403") })
        assertNull(c.fetchLatest())
    }

    @Test fun fetchLatestParsesBody() = runBlocking<Unit> {
        val c = UpdateChecker(httpGet = { sample })
        assertEquals("0.2", c.fetchLatest()!!.versionName)
    }
}
