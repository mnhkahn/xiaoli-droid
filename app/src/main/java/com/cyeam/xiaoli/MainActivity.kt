package com.cyeam.xiaoli

import android.app.Application
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FeishuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WebView.setDataDirectorySuffix("feishu")
    }
}

class MainActivity : ComponentActivity() {
    internal val media = ChatMedia(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Only restore navigation state written by the Feishu-only client.
        super.onCreate(savedInstanceState?.takeIf { it.getBoolean("feishuOnly") })
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { removeLegacyData(applicationContext) }
                .onFailure { Log.w("Xiaoli", "Legacy data cleanup will retry on next launch", it) }
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                FeishuScreen(media, onExit = ::finish)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("feishuOnly", true)
    }
}
