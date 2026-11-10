package com.tara44x.torrentxplayer

/**
 * The magnet link or .torrent file another app handed to Torrent X Player, waiting for the
 * page to collect it (Rust's `take_shared_link` calls [take] through JNI).
 */
object LinkInbox {
  @Volatile private var pending: String? = null

  /** A magnet link, or "torrentfile:" followed by a .torrent file in base64. */
  @JvmStatic
  fun put(link: String) {
    pending = link
  }

  @JvmStatic
  fun take(): String? {
    synchronized(this) {
      val link = pending
      pending = null
      return link
    }
  }
}
