package com.tara44x.torrentxplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * A foreground service that keeps the app's process running while it is in the background, so
 * the stream server keeps feeding VLC (or another player) and downloads continue. It stops with
 * the app's task (`stopWithTask` in the manifest).
 */
class StreamService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = getSystemService(NotificationManager::class.java)
      manager?.createNotificationChannel(
        NotificationChannel(CHANNEL, "Streaming", NotificationManager.IMPORTANCE_LOW)
      )
    }
    val open = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, CHANNEL)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    }
    val notification = builder
      .setContentTitle("Torrent X Player")
      .setContentText("Streaming and downloading")
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentIntent(open)
      .setOngoing(true)
      .build()
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
      } else {
        startForeground(ID, notification)
      }
    } catch (e: Exception) {
      Log.w(TAG, "could not keep running in the background", e)
      stopSelf()
    }
    return START_NOT_STICKY
  }

  companion object {
    private const val TAG = "TorrentXPlayer"
    private const val CHANNEL = "streaming"
    private const val ID = 1

    fun start(context: Context) {
      val intent = Intent(context, StreamService::class.java)
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } catch (e: Exception) {
        Log.w(TAG, "could not start the background service", e)
      }
    }
  }
}
