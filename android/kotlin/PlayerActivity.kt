package com.tara44x.torrentxplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Torrent X Player's built-in video player, powered by libVLC. It plays the app's own loopback
 * stream URL (passed as the "url" extra) while the torrent downloads, with VLC's decoders: any
 * container, Dolby/DTS audio, embedded subtitles.
 */
class PlayerActivity : Activity() {
  private var libVlc: LibVLC? = null
  private var player: MediaPlayer? = null
  private lateinit var videoLayout: VLCVideoLayout
  private lateinit var controls: LinearLayout
  private lateinit var topBar: LinearLayout
  private lateinit var playButton: TextView
  private lateinit var seekBar: SeekBar
  private lateinit var timeText: TextView
  private lateinit var spinner: ProgressBar
  private lateinit var message: TextView

  private val handler = Handler(Looper.getMainLooper())
  private val hideControls = Runnable { setControlsVisible(false) }
  private var dragging = false
  private var lengthMs = 0L
  private var ended = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    goFullscreen()

    val url = intent.getStringExtra(EXTRA_URL)
    if (url.isNullOrEmpty()) {
      finish()
      return
    }
    val title = intent.getStringExtra(EXTRA_TITLE) ?: "Torrent X Player"
    setContentView(buildUi(title))

    try {
      val vlc = LibVLC(this, arrayListOf("--network-caching=3000", "--audio-time-stretch"))
      val mediaPlayer = MediaPlayer(vlc)
      mediaPlayer.attachViews(videoLayout, null, true, false)
      val media = Media(vlc, Uri.parse(url))
      media.setHWDecoderEnabled(true, false)
      media.addOption(":network-caching=3000")
      mediaPlayer.media = media
      media.release()
      mediaPlayer.setEventListener { event -> onPlayerEvent(event) }
      libVlc = vlc
      player = mediaPlayer
      mediaPlayer.play()
    } catch (e: Throwable) {
      Log.e(TAG, "could not start the player", e)
      showMessage("The player could not start: ${e.message}")
    }
    poke()
  }

  override fun onResume() {
    super.onResume()
    goFullscreen()
  }

  override fun onPause() {
    super.onPause()
    player?.pause()
  }

  override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    player?.let {
      it.setEventListener(null)
      it.stop()
      it.detachViews()
      it.release()
    }
    player = null
    libVlc?.release()
    libVlc = null
    super.onDestroy()
  }

  private fun onPlayerEvent(event: MediaPlayer.Event) {
    when (event.type) {
      MediaPlayer.Event.Playing -> {
        spinner.visibility = View.GONE
        message.visibility = View.GONE
        playButton.text = PAUSE
        poke()
      }
      MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> {
        playButton.text = PLAY
        setControlsVisible(true)
      }
      MediaPlayer.Event.Buffering -> {
        spinner.visibility = if (event.buffering < 100f) View.VISIBLE else View.GONE
      }
      MediaPlayer.Event.LengthChanged -> {
        lengthMs = event.lengthChanged
        updateTime(player?.time ?: 0L)
      }
      MediaPlayer.Event.TimeChanged -> {
        spinner.visibility = View.GONE
        if (!dragging) updateTime(event.timeChanged)
      }
      MediaPlayer.Event.EndReached -> {
        ended = true
        playButton.text = PLAY
        setControlsVisible(true)
      }
      MediaPlayer.Event.EncounteredError -> {
        spinner.visibility = View.GONE
        showMessage("This video could not be played.")
      }
      else -> {}
    }
  }

  private fun togglePlay() {
    val mediaPlayer = player ?: return
    if (mediaPlayer.isPlaying) {
      mediaPlayer.pause()
    } else {
      if (ended) {
        ended = false
        mediaPlayer.stop()
      }
      mediaPlayer.play()
    }
    poke()
  }

  private fun skip(deltaMs: Long) {
    val mediaPlayer = player ?: return
    val target = (mediaPlayer.time + deltaMs).coerceAtLeast(0L)
    mediaPlayer.setTime(if (lengthMs > 0) target.coerceAtMost(lengthMs - 1000) else target)
    poke()
  }

  private fun chooseTrack(audio: Boolean) {
    val mediaPlayer = player ?: return
    val tracks = (if (audio) mediaPlayer.audioTracks else mediaPlayer.spuTracks) ?: emptyArray()
    if (tracks.isEmpty()) {
      showToastMessage(if (audio) "No audio tracks" else "No subtitles in this video")
      return
    }
    val current = if (audio) mediaPlayer.audioTrack else mediaPlayer.spuTrack
    val names: Array<CharSequence> = Array(tracks.size) { i -> tracks[i].name ?: "Track ${tracks[i].id}" }
    val checked = tracks.indexOfFirst { it.id == current }
    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
      .setTitle(if (audio) "Audio" else "Subtitles")
      .setSingleChoiceItems(names, checked) { dialog, which ->
        val id = tracks[which].id
        if (audio) mediaPlayer.setAudioTrack(id) else mediaPlayer.setSpuTrack(id)
        dialog.dismiss()
      }
      .show()
    poke()
  }

  private fun updateTime(timeMs: Long) {
    if (lengthMs > 0) {
      seekBar.max = (lengthMs / 1000).toInt()
      seekBar.progress = (timeMs / 1000).toInt()
    }
    timeText.text = "${format(timeMs)} / ${if (lengthMs > 0) format(lengthMs) else "--:--"}"
  }

  private fun poke() {
    setControlsVisible(true)
    handler.removeCallbacks(hideControls)
    if (player?.isPlaying == true) handler.postDelayed(hideControls, HIDE_AFTER_MS)
  }

  private fun setControlsVisible(visible: Boolean) {
    val state = if (visible) View.VISIBLE else View.GONE
    controls.visibility = state
    topBar.visibility = state
    if (!visible) goFullscreen()
  }

  private fun showMessage(text: String) {
    message.text = text
    message.visibility = View.VISIBLE
    setControlsVisible(true)
  }

  private fun showToastMessage(text: String) {
    android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
  }

  @Suppress("DEPRECATION")
  private fun goFullscreen() {
    window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
      or View.SYSTEM_UI_FLAG_FULLSCREEN
      or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
      or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
      or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
      or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
  }

  // ---- Layout (built in code, neon style) -------------------------------------------------

  private fun dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

  private fun neonButton(label: String, big: Boolean = false, onClick: () -> Unit): TextView =
    TextView(this).apply {
      text = label
      setTextColor(CYAN)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, if (big) 26f else 15f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setShadowLayer(14f, 0f, 0f, CYAN)
      val padH = if (big) dp(22) else dp(14)
      val padV = if (big) dp(10) else dp(8)
      setPadding(padH, padV, padH, padV)
      background = GradientDrawable().apply {
        cornerRadius = dp(28).toFloat()
        setColor(Color.argb(40, 0, 229, 255))
        setStroke(dp(2), CYAN)
      }
      setOnClickListener { onClick() }
    }

  private fun buildUi(title: String): View {
    val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

    videoLayout = VLCVideoLayout(this)
    root.addView(videoLayout, FrameLayout.LayoutParams(MATCH, MATCH))

    // Tapping the video shows or hides the controls.
    val tapCatcher = View(this).apply {
      setOnClickListener {
        if (controls.visibility == View.VISIBLE) setControlsVisible(false) else poke()
      }
    }
    root.addView(tapCatcher, FrameLayout.LayoutParams(MATCH, MATCH))

    spinner = ProgressBar(this).apply {
      isIndeterminate = true
      indeterminateTintList = ColorStateList.valueOf(MAGENTA)
    }
    root.addView(spinner, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))

    message = TextView(this).apply {
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      gravity = Gravity.CENTER
      setPadding(dp(18), dp(12), dp(18), dp(12))
      background = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(Color.argb(200, 10, 12, 24))
      }
      visibility = View.GONE
    }
    root.addView(
      message,
      FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { topMargin = dp(90) }
    )

    topBar = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(16), dp(14), dp(16), dp(24))
      background = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.argb(190, 0, 0, 0), Color.TRANSPARENT)
      )
      addView(neonButton("‹ Back") { finish() })
      addView(TextView(this@PlayerActivity).apply {
        text = title
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(14), 0, 0, 0)
      }, LinearLayout.LayoutParams(0, WRAP, 1f))
    }
    root.addView(topBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

    seekBar = SeekBar(this).apply {
      progressTintList = ColorStateList.valueOf(CYAN)
      thumbTintList = ColorStateList.valueOf(MAGENTA)
      progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
      setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
          if (fromUser) timeText.text = "${format(progress * 1000L)} / ${format(lengthMs)}"
        }

        override fun onStartTrackingTouch(bar: SeekBar) {
          dragging = true
          handler.removeCallbacks(hideControls)
        }

        override fun onStopTrackingTouch(bar: SeekBar) {
          dragging = false
          player?.setTime(bar.progress * 1000L)
          poke()
        }
      })
    }

    timeText = TextView(this).apply {
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      text = "0:00 / --:--"
    }

    playButton = neonButton(PAUSE, big = true) { togglePlay() }

    val buttons = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      val gap = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(10) }
      addView(neonButton("⟲ 10") { skip(-10_000) }, gap)
      addView(playButton, gap)
      addView(neonButton("10 ⟳") { skip(10_000) }, gap)
      addView(timeText, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) })
      addView(neonButton("Audio") { chooseTrack(audio = true) }, gap)
      addView(neonButton("Subtitles") { chooseTrack(audio = false) })
    }

    controls = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(28), dp(16), dp(14))
      background = GradientDrawable(
        GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(Color.argb(210, 0, 0, 0), Color.TRANSPARENT)
      )
      addView(seekBar, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
      addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP))
    }
    root.addView(controls, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
    return root
  }

  companion object {
    const val EXTRA_URL = "url"
    const val EXTRA_TITLE = "title"
    private const val TAG = "TorrentXPlayer"
    private const val HIDE_AFTER_MS = 3500L
    private const val PLAY = "▶"
    private const val PAUSE = "❚❚"
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val CYAN = Color.rgb(0, 229, 255)
    private val MAGENTA = Color.rgb(255, 43, 214)

    private fun format(ms: Long): String {
      val total = (ms / 1000).coerceAtLeast(0)
      val h = total / 3600
      val m = (total % 3600) / 60
      val s = total % 60
      return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
  }
}
