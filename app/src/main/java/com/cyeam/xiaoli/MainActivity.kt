package com.cyeam.xiaoli

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var clients by mutableStateOf<List<Brand>?>(null)
    private fun refreshClients() {
        clients = Brand.entries.filter { packageManager.getLaunchIntentForPackage(it.clientPackage) != null }
    }
    private val clientChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) refreshClients()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
            Brand.entries.forEach { addDataSchemeSpecificPart(it.clientPackage, android.os.PatternMatcher.PATTERN_LITERAL) }
        }
        ContextCompat.registerReceiver(this, clientChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshClients()
    }

    override fun onStop() {
        unregisterReceiver(clientChanges)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState?.takeIf { it.getBoolean("openPlatform") })
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { removeLegacyData(applicationContext) }
                .onFailure { Log.w("Xiaoli", "Legacy data cleanup will retry on next launch", it) }
        }
        val chat = ViewModelProvider(this)[ChatModel::class.java]
        val updates = ViewModelProvider(this)[AppUpdateModel::class.java]
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                clients?.let { ChatScreen(chat, it, onCheckUpdate = { updates.check() }) }
                AppUpdateDialog(updates)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("openPlatform", true)
    }
}
