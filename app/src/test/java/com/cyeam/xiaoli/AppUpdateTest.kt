package com.cyeam.xiaoli

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    private val tagUrl = "https://github.com/mnhkahn/xiaoli-droid/releases/download/v0.0.9/xiaoli.apk"
    private val digest = "559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd"
    private fun manifest(url: String = tagUrl, sha256: String = digest) = JSONObject()
        .put("versionName", "0.0.9").put("versionCode", 1000001014).put("sha256", sha256)
        .put("apkUrl", url).put("pgyerUrl", "https://www.pgyer.com/xiaoli-android-2").toString()

    @Test fun comparesAndroidVersionCodesAndUsesTheReleaseApk() {
        assertEquals(AppRelease("0.0.9", 1000001014, tagUrl, digest), newerVersion(manifest(), 1000001013))
        val numericTag = tagUrl.replace("/v0.0.9/", "/0.0.9/")
        assertEquals(numericTag, newerVersion(manifest(numericTag), 1)?.url)
        assertNull(newerVersion(manifest(), 1000001014))
        assertNull(newerVersion(manifest(), 1000001015))
    }

    @Test fun rejectsIncompleteOrMismatchedReleaseAssets() {
        assertThrows(org.json.JSONException::class.java) { newerVersion("""{"versionName":"0.0.9"}""", 1) }
        for (url in listOf("https://www.pgyer.com/xiaoli-android-2", tagUrl.replace("github.com", "github.com.example"),
            tagUrl.replace("/v0.0.9/", "/v0.0.8/"))) {
            assertThrows(IllegalArgumentException::class.java) { newerVersion(manifest(url), 1) }
        }
        assertThrows(IllegalArgumentException::class.java) { newerVersion(manifest(sha256 = ""), 1) }
    }

    @Test fun rejectsTruncatedOrChangedApkBeforeInstallation() {
        val tmp = File.createTempFile("update", ".apk")
        try {
            copyVerifiedUpdate("A".byteInputStream(), tmp, digest)
            assertEquals("A", tmp.readText())
            for (bad in listOf("", "B")) {
                assertThrows(IllegalStateException::class.java) { copyVerifiedUpdate(bad.byteInputStream(), tmp, digest) }
            }
        } finally { tmp.delete() }
    }
}
