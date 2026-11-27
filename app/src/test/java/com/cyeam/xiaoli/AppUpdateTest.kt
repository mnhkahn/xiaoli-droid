package com.cyeam.xiaoli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AppUpdateTest {
    private val manifest = """{"versionName":"0.0.7","versionCode":1000001012}"""

    @Test fun comparesAndroidVersionCodes() {
        assertEquals("0.0.7", newerVersion(manifest, 1000001009))
        assertNull(newerVersion(manifest, 1000001012))
        assertNull(newerVersion(manifest, 1000001013))
    }

    @Test fun incompleteManifestCannotReportUpToDate() {
        assertThrows(org.json.JSONException::class.java) {
            newerVersion("""{"versionName":"0.0.7"}""", 1000001012)
        }
    }
}
