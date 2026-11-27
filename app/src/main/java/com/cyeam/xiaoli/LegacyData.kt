package com.cyeam.xiaoli

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ShortcutManager
import java.io.File
import java.security.KeyStore

@Synchronized
internal fun removeLegacyData(context: Context) {
    val current = context.getSharedPreferences("open-platform", Context.MODE_PRIVATE)
    if (current.getBoolean("legacyRemoved", false)) return

    for (name in listOf("connection", "preferences", "feishu")) {
        check(context.deleteSharedPreferences(name))
    }
    KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
        for (alias in listOf("xiaoli-device", "xiaoli-feishu-channels")) if (containsAlias(alias)) deleteEntry(alias)
    }
    val files = listOf(File(context.filesDir, "drafts"), File(context.filesDir, "attachments"),
        File(context.dataDir, "app_webview"), File(context.dataDir, "app_webview_feishu"))
    val caches = listOf("photos", "exports", "temporary", "updates", "image_cache").map { File(context.cacheDir, it) }
    val recordings = context.cacheDir.listFiles().orEmpty().filter {
        it.name.endsWith(".ogg") && (it.name.startsWith("speech-") || it.name.startsWith("recognize-"))
    }
    for (file in files + caches + recordings) {
        check(!file.exists() || file.deleteRecursively())
    }
    context.getSystemService(NotificationManager::class.java).apply {
        deleteNotificationChannel("connection")
        deleteNotificationChannel("results")
    }
    context.getSystemService(ShortcutManager::class.java).disableShortcuts(listOf("assistant"))
    check(current.edit().putBoolean("legacyRemoved", true).commit())
}
