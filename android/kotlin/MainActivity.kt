package com.tara44x.torrentxplayer

import android.os.Bundle
import androidx.activity.enableEdgeToEdge

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    // Keeps streaming and downloading alive while another player (VLC) is in front.
    StreamService.start(this)
  }
}
