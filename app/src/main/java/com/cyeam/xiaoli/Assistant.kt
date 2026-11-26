package com.cyeam.xiaoli

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import coil.ImageLoader
import coil.ImageLoaderFactory

class XiaoliApp : Application(), ImageLoaderFactory {
    override fun newImageLoader() =
        ImageLoader.Builder(this).crossfade(false).respectCacheHeaders(true).build()
}

class AssistantService : VoiceInteractionService() {
    private var ready = false
    private var pendingShow = false

    override fun onReady() {
        super.onReady()
        ready = true
        if (pendingShow) {
            pendingShow = false
            showSession(Bundle(), 0)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_ASSIST) {
            if (ready) showSession(Bundle(), 0) else pendingShow = true
        }
        return START_NOT_STICKY
    }

    override fun onShutdown() {
        ready = false
        pendingShow = false
        super.onShutdown()
    }
}

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = XiaoliSession(this)
}

class XiaoliSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // The selected VoiceInteractionService is system-bound; launch the regular
        // app task so its lifecycle is independent from the transient voice session.
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("assistant", true)
        )
        finish()
    }
}

class AssistantTile : TileService() {
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // Intent overload is only called below API 34.
    override fun onClick() {
        super.onClick()
        val intent =
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("assistant", true)
        if (android.os.Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }
}
