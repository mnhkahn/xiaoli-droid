package com.cyeam.xiaoli

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState?.takeIf { it.getBoolean("openPlatform") })
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { removeLegacyData(applicationContext) }
                .onFailure { Log.w("Xiaoli", "Legacy data cleanup will retry on next launch", it) }
        }
        val chat = ViewModelProvider(this)[ChatModel::class.java]
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                ChatScreen(chat)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("openPlatform", true)
    }
}
