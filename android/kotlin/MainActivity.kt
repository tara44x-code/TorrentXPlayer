package com.tara44x.torrentxplayer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.enableEdgeToEdge

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    // Keeps streaming and downloading alive while another player (VLC) is in front.
    StreamService.start(this)
    receive(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    receive(intent)
  }

  /** A magnet link or .torrent file opened or shared from another app (browser, file manager). */
  private fun receive(intent: Intent?) {
    if (intent == null) return
    try {
      when (intent.action) {
        Intent.ACTION_VIEW -> {
          val uri = intent.data ?: return
          if (uri.scheme.equals("magnet", ignoreCase = true)) {
            LinkInbox.put(uri.toString())
          } else {
            readTorrent(uri)
          }
        }
        Intent.ACTION_SEND -> {
          val text = intent.getStringExtra(Intent.EXTRA_TEXT)
          val magnet = text?.let { Regex("magnet:\\?[^\\s\"'<>]+").find(it)?.value }
          if (magnet != null) {
            LinkInbox.put(magnet)
          } else {
            sharedStream(intent)?.let { readTorrent(it) }
          }
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "could not read the shared link", e)
    }
  }

  @Suppress("DEPRECATION")
  private fun sharedStream(intent: Intent): Uri? =
    if (Build.VERSION.SDK_INT >= 33) {
      intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
      intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

  private fun readTorrent(uri: Uri) {
    Thread {
      try {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@Thread
        if (bytes.isNotEmpty() && bytes.size <= 32 * 1024 * 1024) {
          LinkInbox.put("torrentfile:" + Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
      } catch (e: Exception) {
        Log.w(TAG, "could not read the .torrent file", e)
      }
    }.start()
  }

  companion object {
    private const val TAG = "TorrentXPlayer"
  }
}
