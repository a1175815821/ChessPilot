package com.chessassist

import android.content.Intent
import androidx.lifecycle.LifecycleService
import com.chessassist.engine.EngineLoop

/**
 * Foreground service that owns the engine + overlay. Survives Duolingo being the
 * foreground app because analysis happens here, in the companion's own process.
 */
class AssistService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        BridgeSettings.load(this)
        Notify.ensureChannel(this)  // must exist before startForeground on O+
        val notification = androidx.core.app.NotificationCompat.Builder(
            this, Notify.CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("象棋助手")
            .setContentText("本地引擎运行中 — 请打开多邻国国际象棋")
            .setOngoing(true)
            .build()
        startForeground(Notify.NOTIF_ID, notification)
        BridgeServer.start()
        EngineLoop.start(applicationContext)
        OverlayManager.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                EngineLoop.stop()
                stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        EngineLoop.stop()
        BridgeServer.stop()
        OverlayManager.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.chessassist.STOP"
    }
}
