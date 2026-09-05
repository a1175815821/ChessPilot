package com.chessassist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notify {
    const val CHANNEL_ID = "chessassist"
    const val NOTIF_ID = 42

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "ChessAssist", NotificationManager.IMPORTANCE_LOW
            )
        )
    }
}
