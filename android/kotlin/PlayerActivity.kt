package com.tara44x.torrentxplayer

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Rational
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
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
  private var resumeAfterPause = false
  private var viewsAttached = false
  private var videoTitle = ""
  private var scaleIndex = DEFAULT_SCALE
  private lateinit var prefs: SharedPreferences
  private lateinit var scaleButton: TextView
  private lateinit var infoText: TextView
  private val hideInfo = Runnable { infoText.visibility = View.GONE }

  // Gestures: vertical swipe on the right = volume, on the left = brightness, sideways = seek.
  private enum class Drag { NONE, VOLUME, BRIGHTNESS, SEEK }
  private var drag = Drag.NONE
  private var downX = 0f
  private var downY = 0f
  private var dragStart = 0f
  private var seekStartMs = 0L
  private var seekTargetMs = 0L
  private var speedHeld = false
  private var speed = 1f
  private var volumePercent = -1f
  private var locked = false
  private var resumeChecked = false
  private lateinit var audio: AudioManager
  private lateinit var touchView: View
  private lateinit var speedButton: TextView
  private lateinit var unlockButton: TextView
  private val hideUnlock = Runnable { unlockButton.visibility = View.GONE }

  /** Pauses when headphones are unplugged, like any good player. */
  private val noisy = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) player?.pause()
    }
  }

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
    videoTitle = title
    prefs = getSharedPreferences("player", Context.MODE_PRIVATE)
    audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    if (Build.VERSION.SDK_INT >= 33) {
      registerReceiver(noisy, noisyFilter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      registerReceiver(noisy, noisyFilter)
    }
    scaleIndex = prefs.getInt(KEY_SCALE, DEFAULT_SCALE).coerceIn(0, SCALES.size - 1)
    setContentView(buildUi(title))

    try {
      val vlc = LibVLC(this, arrayListOf("--network-caching=3000", "--audio-time-stretch"))
      val mediaPlayer = MediaPlayer(vlc)
      mediaPlayer.attachViews(videoLayout, null, true, false)
      viewsAttached = true
      val media = Media(vlc, Uri.parse(url))
      media.setHWDecoderEnabled(true, false)
      media.addOption(":network-caching=3000")
      mediaPlayer.media = media
      media.release()
      mediaPlayer.setEventListener { event -> onPlayerEvent(event) }
      libVlc = vlc
      player = mediaPlayer
      applyScale(announce = false)
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
    if (resumeAfterPause) {
      resumeAfterPause = false
      player?.play()
    }
  }

  override fun onPause() {
    super.onPause()
    savePosition()
    // Picture-in-picture keeps playing in its small window.
    if (isInPictureInPictureMode) return
    resumeAfterPause = player?.isPlaying == true
    player?.pause()
  }

  override fun onStart() {
    super.onStart()
    // Back from the file picker (or the home screen): the video surface was destroyed while we
    // were away, so give libVLC the new one and restart its video output.
    val mediaPlayer = player ?: return
    if (!viewsAttached) {
      mediaPlayer.attachViews(videoLayout, null, true, false)
      viewsAttached = true
      mediaPlayer.setVideoTrackEnabled(true)
      applyScale(announce = false)
    }
  }

  override fun onStop() {
    super.onStop()
    val mediaPlayer = player ?: return
    // Also when the picture-in-picture window is closed.
    if (!isFinishing) mediaPlayer.pause()
    if (viewsAttached) {
      mediaPlayer.setVideoTrackEnabled(false)
      mediaPlayer.detachViews()
      viewsAttached = false
    }
  }

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    // Home button while playing: keep watching in a small window.
    if (player?.isPlaying == true) enterPip()
  }

  override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    if (isInPictureInPictureMode) {
      setControlsVisible(false)
      infoText.visibility = View.GONE
    }
  }

  private fun enterPip() {
    if (Build.VERSION.SDK_INT < 26) {
      toast("Picture-in-picture needs Android 8 or newer.")
      return
    }
    try {
      enterPictureInPictureMode(
        PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
      )
    } catch (e: Exception) {
      toast("Picture-in-picture is not available.")
    }
  }

  @Deprecated("Activity result API of the platform Activity")
  @Suppress("DEPRECATION")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != PICK_SUBTITLE || resultCode != RESULT_OK) return
    val uri = data?.data ?: return
    val language = preferredLanguage()
    Thread {
      try {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
          ?: throw IOException("the file cannot be read")
        if (bytes.size > 8 * 1024 * 1024) throw IOException("the file is too large")
        val name = uri.lastPathSegment ?: "subtitle.srt"
        val (text, ext) = SubtitleSources.extract(bytes, name, language)
        runOnUiThread { addSubtitle(text, ext) }
      } catch (e: Exception) {
        runOnUiThread { toast("Could not load the subtitle: ${e.message}") }
      }
    }.start()
  }

  override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    savePosition()
    try {
      unregisterReceiver(noisy)
    } catch (e: Exception) {
      // Not registered: the player never started.
    }
    player?.let {
      it.setEventListener(null)
      it.stop()
      if (viewsAttached) it.detachViews()
      viewsAttached = false
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
        resumeSavedPosition()
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
        prefs.edit().remove(positionKey()).apply()
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

  private fun chooseAudio() {
    val mediaPlayer = player ?: return
    val tracks = mediaPlayer.audioTracks ?: emptyArray()
    val current = mediaPlayer.audioTrack
    val items = ArrayList<CharSequence>()
    tracks.forEach { items.add(it.name ?: "Track ${it.id}") }
    val firstAction = items.size
    items.add("⏱  Audio timing (delay)…")
    dialog()
      .setTitle("Audio")
      .setSingleChoiceItems(items.toTypedArray(), tracks.indexOfFirst { it.id == current }) { box, which ->
        box.dismiss()
        if (which < firstAction) mediaPlayer.setAudioTrack(tracks[which].id) else delayDialog(audioDelay = true)
      }
      .show()
    poke()
  }

  /** Shifts subtitles or audio earlier or later, in steps of 0.1 s. */
  private fun delayDialog(audioDelay: Boolean) {
    val mediaPlayer = player ?: return
    var delayMs = (if (audioDelay) mediaPlayer.audioDelay else mediaPlayer.spuDelay) / 1000L
    fun describe(): String {
      val seconds = String.format(Locale.US, "%+.1f s", delayMs / 1000.0)
      val what = if (audioDelay) "Audio" else "Subtitles"
      return if (delayMs == 0L) "$what are in sync with the video (0.0 s)." else "$what: $seconds"
    }
    val box = dialog()
      .setTitle(if (audioDelay) "Audio timing" else "Subtitle timing")
      .setMessage(describe())
      .setPositiveButton("+0.1 s", null)
      .setNegativeButton("−0.1 s", null)
      .setNeutralButton("Reset", null)
      .show()
    fun update() {
      if (audioDelay) mediaPlayer.setAudioDelay(delayMs * 1000L) else mediaPlayer.setSpuDelay(delayMs * 1000L)
      box.setMessage(describe())
    }
    box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { delayMs += 100; update() }
    box.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { delayMs -= 100; update() }
    box.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { delayMs = 0; update() }
  }

  private fun chooseSpeed() {
    val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
    val labels = Array<CharSequence>(speeds.size) { i -> speedLabel(speeds[i]) }
    dialog()
      .setTitle("Playback speed")
      .setSingleChoiceItems(labels, speeds.indexOfFirst { it == speed }) { box, which ->
        box.dismiss()
        speed = speeds[which]
        player?.setRate(speed)
        speedButton.text = speedLabel(speed)
      }
      .show()
    poke()
  }

  private fun speedLabel(value: Float): String =
    (if (value % 1f == 0f) value.toInt().toString() else value.toString().trimEnd('0')) + "×"

  // ---- Locking the screen -------------------------------------------------------------------

  private fun lock() {
    locked = true
    handler.removeCallbacks(hideControls)
    controls.visibility = View.GONE
    topBar.visibility = View.GONE
    goFullscreen()
    showInfo("🔒 Screen locked")
    showUnlockButton()
  }

  private fun unlock() {
    locked = false
    unlockButton.visibility = View.GONE
    showInfo("🔓 Unlocked")
    poke()
  }

  private fun showUnlockButton() {
    unlockButton.visibility = View.VISIBLE
    handler.removeCallbacks(hideUnlock)
    handler.postDelayed(hideUnlock, 3000)
  }

  // ---- Swipe gestures -----------------------------------------------------------------------

  private fun showInfo(text: String, persistent: Boolean = false) {
    infoText.text = text
    infoText.visibility = View.VISIBLE
    handler.removeCallbacks(hideInfo)
    if (!persistent) handler.postDelayed(hideInfo, 900)
  }

  private fun currentVolumePercent(): Float {
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    // Above 100 % is the player's own boost, while the system volume is at its maximum.
    return if (volumePercent > 100f && current == max) volumePercent else current * 100f / max
  }

  private fun setVolumePercent(value: Float) {
    val percent = value.coerceIn(0f, 200f)
    volumePercent = percent
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    try {
      audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent.coerceAtMost(100f) / 100f * max).roundToInt(), 0)
    } catch (e: SecurityException) {
      // Do Not Disturb can refuse volume changes.
    }
    player?.setVolume(if (percent > 100f) percent.toInt() else 100)
    val icon = if (percent <= 0f) "🔇" else "🔊"
    showInfo(if (percent > 100f) "$icon ${percent.toInt()}%  BOOST" else "$icon ${percent.toInt()}%", persistent = true)
  }

  private fun currentBrightness(): Float {
    val own = window.attributes.screenBrightness
    if (own >= 0f) return own
    return try {
      (Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f).coerceIn(0.01f, 1f)
    } catch (e: Exception) {
      0.5f
    }
  }

  private fun setBrightness(value: Float) {
    val level = value.coerceIn(0.01f, 1f)
    val attributes = window.attributes
    attributes.screenBrightness = level
    window.attributes = attributes
    showInfo("☀ ${(level * 100).roundToInt()}%", persistent = true)
  }

  /** Handles one touch event on the video. Returns true: the video takes every touch. */
  private fun onVideoTouch(event: MotionEvent, taps: GestureDetector, pinches: ScaleGestureDetector): Boolean {
    if (locked) {
      if (event.actionMasked == MotionEvent.ACTION_UP) showUnlockButton()
      return true
    }
    pinches.onTouchEvent(event)
    if (pinches.isInProgress || event.pointerCount > 1) {
      drag = Drag.NONE
      return true
    }
    taps.onTouchEvent(event)
    val width = touchView.width.coerceAtLeast(1)
    val height = touchView.height.coerceAtLeast(1)
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        downX = event.x
        downY = event.y
        drag = Drag.NONE
      }
      MotionEvent.ACTION_MOVE -> {
        val dx = event.x - downX
        val dy = event.y - downY
        if (drag == Drag.NONE && !speedHeld) {
          val slop = dp(20)
          if (abs(dy) > slop && abs(dy) > abs(dx) * 1.2f) {
            drag = if (downX > width / 2f) Drag.VOLUME else Drag.BRIGHTNESS
            dragStart = if (drag == Drag.VOLUME) currentVolumePercent() else currentBrightness()
            downY = event.y
          } else if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f && lengthMs > 0) {
            drag = Drag.SEEK
            seekStartMs = player?.time ?: 0L
            downX = event.x
          }
        }
        val dyNow = event.y - downY
        val dxNow = event.x - downX
        when (drag) {
          Drag.VOLUME -> setVolumePercent(dragStart - dyNow / (height * 0.8f) * 100f)
          Drag.BRIGHTNESS -> setBrightness(dragStart - dyNow / (height * 0.8f))
          Drag.SEEK -> {
            val delta = (dxNow / width * 90_000f).toLong()
            seekTargetMs = (seekStartMs + delta).coerceIn(0L, (lengthMs - 1000L).coerceAtLeast(0L))
            val shift = seekTargetMs - seekStartMs
            val arrow = if (shift >= 0) "⏩ +" else "⏪ −"
            showInfo("$arrow${format(abs(shift))}\n${format(seekTargetMs)} / ${format(lengthMs)}", persistent = true)
          }
          Drag.NONE -> {}
        }
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        if (drag == Drag.SEEK) {
          player?.setTime(seekTargetMs)
          updateTime(seekTargetMs)
        }
        if (speedHeld) {
          speedHeld = false
          player?.setRate(speed)
        }
        if (drag != Drag.NONE || infoText.visibility == View.VISIBLE) {
          handler.removeCallbacks(hideInfo)
          handler.postDelayed(hideInfo, 600)
        }
        drag = Drag.NONE
      }
    }
    return true
  }

  // ---- Resume where you stopped -------------------------------------------------------------

  private fun positionKey(): String = "pos:$videoTitle"

  private fun savePosition() {
    if (!this::prefs.isInitialized) return
    val time = player?.time ?: return
    if (time > 15_000L && (lengthMs <= 0L || time < lengthMs - 30_000L)) {
      prefs.edit().putLong(positionKey(), time).apply()
    }
  }

  private fun resumeSavedPosition() {
    if (resumeChecked) return
    resumeChecked = true
    val saved = prefs.getLong(positionKey(), 0L)
    if (saved > 15_000L && (lengthMs <= 0L || saved < lengthMs - 30_000L)) {
      player?.setTime(saved)
      showInfo("⏮ Resumed at ${format(saved)}")
    }
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

  private fun dialog(): AlertDialog.Builder =
    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

  private fun toast(text: String) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
  }

  /** The subtitle menu: the video's own tracks, then loading from the device or online. */
  private fun chooseSubtitles() {
    val mediaPlayer = player ?: return
    val tracks = mediaPlayer.spuTracks ?: emptyArray()
    val current = mediaPlayer.spuTrack
    val items = ArrayList<CharSequence>()
    tracks.forEach { items.add(it.name ?: "Track ${it.id}") }
    val firstAction = items.size
    items.add("📁  Load from device…")
    items.add("🌐  Search online…")
    items.add("⏱  Subtitle timing (delay)…")
    val checked = tracks.indexOfFirst { it.id == current }
    dialog()
      .setTitle("Subtitles")
      .setSingleChoiceItems(items.toTypedArray(), checked) { box, which ->
        box.dismiss()
        when {
          which < firstAction -> mediaPlayer.setSpuTrack(tracks[which].id)
          which == firstAction -> pickSubtitleFile()
          which == firstAction + 1 -> searchOnline()
          else -> delayDialog(audioDelay = false)
        }
      }
      .show()
    poke()
  }

  @Suppress("DEPRECATION")
  private fun pickSubtitleFile() {
    val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
      .addCategory(Intent.CATEGORY_OPENABLE)
      .setType("*/*")
      // Not the user leaving: no picture-in-picture while choosing a file.
      .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    try {
      startActivityForResult(picker, PICK_SUBTITLE)
    } catch (e: Exception) {
      toast("No file picker is available on this device.")
    }
  }

  private fun preferredLanguage(): String = prefs.getString(KEY_LANGUAGE, "ar") ?: "ar"

  /** Adds a subtitle file (UTF-8 bytes) to the playing video and shows it. */
  private fun addSubtitle(text: ByteArray, ext: String) {
    val mediaPlayer = player ?: return
    try {
      val dir = File(cacheDir, "subtitles").apply { mkdirs() }
      val file = File(dir, "sub-${System.currentTimeMillis()}.$ext")
      file.writeBytes(text)
      if (mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), true)) {
        toast("Subtitle added ✓")
      } else {
        toast("The player could not use this subtitle.")
      }
    } catch (e: Exception) {
      toast("Could not add the subtitle: ${e.message}")
    }
  }

  /** Online search: the title guessed from the file name, in the chosen language. */
  private fun searchOnline() {
    val guess = SubtitleSources.guess(videoTitle)
    val builder = dialog()
    val ctx = builder.context
    val layout = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(22), dp(8), dp(22), 0)
    }
    val queryInput = EditText(ctx).apply {
      setText(guess.title)
      setSingleLine()
      hint = "Movie or series name"
    }
    val languages = SubtitleSources.LANGUAGES
    val spinner = Spinner(ctx).apply {
      adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, languages.map { it.name })
      setSelection(languages.indexOfFirst { it.code == preferredLanguage() }.coerceAtLeast(0))
    }
    val sources = mutableListOf("Podnapisi")
    if (!prefs.getString(KEY_SUBDL, null).isNullOrBlank()) sources += "SubDL"
    if (!prefs.getString(KEY_SUBSOURCE, null).isNullOrBlank()) sources += "SubSource"
    if (!prefs.getString(KEY_OPENSUBTITLES, null).isNullOrBlank()) sources += "OpenSubtitles"
    val details = listOfNotNull(
      guess.year?.let { "Year $it" },
      if (guess.season != null && guess.episode != null) "S${guess.season} E${guess.episode}" else null,
    ).joinToString(" · ")
    val note = TextView(ctx).apply {
      text = buildString {
        if (details.isNotEmpty()) append(details).append('\n')
        append("Sources: ").append(sources.joinToString(", "))
        if (sources.size < 4) append("\nAdd free API keys (SubSource, SubDL, OpenSubtitles) to search more sites.")
      }
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
      setPadding(0, dp(10), 0, 0)
    }
    layout.addView(queryInput)
    layout.addView(spinner)
    layout.addView(note)
    builder
      .setTitle("Search subtitles online")
      .setView(layout)
      .setPositiveButton("Search") { _, _ ->
        val language = languages[spinner.selectedItemPosition].code
        prefs.edit().putString(KEY_LANGUAGE, language).apply()
        val title = queryInput.text.toString().trim().ifEmpty { guess.title }
        runSearch(guess.copy(title = title), language)
      }
      .setNeutralButton("API keys") { _, _ -> editKeys() }
      .setNegativeButton("Cancel", null)
      .show()
  }

  private fun runSearch(guess: VideoGuess, language: String) {
    val progress = dialog()
      .setTitle("Searching…")
      .setMessage("Looking for \"${guess.title}\" on subtitle sites")
      .show()
    val subdlKey = prefs.getString(KEY_SUBDL, null)
    val openSubtitlesKey = prefs.getString(KEY_OPENSUBTITLES, null)
    val subsourceKey = prefs.getString(KEY_SUBSOURCE, null)
    Thread {
      val (results, errors) = SubtitleSources.search(guess, language, subdlKey, openSubtitlesKey, subsourceKey)
      runOnUiThread {
        progress.dismiss()
        if (isFinishing || isDestroyed) return@runOnUiThread
        if (results.isEmpty()) {
          val why = if (errors.isEmpty()) "" else "\n\n" + errors.joinToString("\n")
          dialog()
            .setTitle("No subtitles found")
            .setMessage("Try a shorter name or another language.$why")
            .setPositiveButton("Search again") { _, _ -> searchOnline() }
            .setNegativeButton("Close", null)
            .show()
          return@runOnUiThread
        }
        val labels = Array<CharSequence>(results.size) { i -> "[${results[i].source}]  ${results[i].label}" }
        dialog()
          .setTitle("${results.size} subtitles found")
          .setItems(labels) { _, which -> downloadSubtitle(results[which], language) }
          .setNegativeButton("Close", null)
          .show()
      }
    }.start()
  }

  private fun downloadSubtitle(result: SubResult, language: String) {
    toast("Downloading subtitle…")
    Thread {
      try {
        val bytes = result.download()
        val (text, ext) = SubtitleSources.extract(bytes, result.label, language)
        runOnUiThread { addSubtitle(text, ext) }
      } catch (e: Exception) {
        runOnUiThread { toast("Download failed: ${e.message}") }
      }
    }.start()
  }

  private fun editKeys() {
    val builder = dialog()
    val ctx = builder.context
    val layout = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(22), dp(8), dp(22), 0)
    }
    val subdl = EditText(ctx).apply {
      setSingleLine()
      hint = "SubDL API key (subdl.com → Panel → API)"
      setText(prefs.getString(KEY_SUBDL, ""))
    }
    val openSubtitles = EditText(ctx).apply {
      setSingleLine()
      hint = "OpenSubtitles.com API key (Consumers)"
      setText(prefs.getString(KEY_OPENSUBTITLES, ""))
    }
    val subsource = EditText(ctx).apply {
      setSingleLine()
      hint = "SubSource API key (Subscene archive)"
      setText(prefs.getString(KEY_SUBSOURCE, ""))
    }
    layout.addView(subsource)
    layout.addView(subdl)
    layout.addView(openSubtitles)
    builder
      .setTitle("Subtitle site keys (free)")
      .setView(layout)
      .setPositiveButton("Save") { _, _ ->
        prefs.edit()
          .putString(KEY_SUBDL, subdl.text.toString().trim())
          .putString(KEY_OPENSUBTITLES, openSubtitles.text.toString().trim())
          .putString(KEY_SUBSOURCE, subsource.text.toString().trim())
          .apply()
        toast("Saved")
        searchOnline()
      }
      .setNegativeButton("Cancel", null)
      .show()
  }

  /** Applies the current screen mode (fit, fill, stretch, ...) and remembers it. */
  private fun applyScale(announce: Boolean = true) {
    val (scale, label) = SCALES[scaleIndex]
    player?.videoScale = scale
    prefs.edit().putInt(KEY_SCALE, scaleIndex).apply()
    if (this::scaleButton.isInitialized) scaleButton.text = "⛶ $label"
    if (announce) {
      infoText.text = label
      infoText.visibility = View.VISIBLE
      handler.removeCallbacks(hideInfo)
      handler.postDelayed(hideInfo, 1200)
    }
  }

  private fun setScale(index: Int) {
    if (index == scaleIndex) return
    scaleIndex = index
    applyScale()
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
    if (visible && (locked || isInPictureInPictureMode)) return
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
    // Tap: show or hide the controls. Double tap: fit / fill screen. Pinch: fill or fit.
    val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
      override fun onDown(e: MotionEvent): Boolean = true

      override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        if (controls.visibility == View.VISIBLE) setControlsVisible(false) else poke()
        return true
      }

      override fun onDoubleTap(e: MotionEvent): Boolean {
        val third = touchView.width / 3f
        when {
          e.x < third -> {
            skip(-10_000)
            showInfo("⏪ 10 s")
          }
          e.x > third * 2 -> {
            skip(10_000)
            showInfo("⏩ 10 s")
          }
          else -> setScale(if (scaleIndex == SCALE_FILL_SCREEN) SCALE_FIT else SCALE_FILL_SCREEN)
        }
        return true
      }

      override fun onLongPress(e: MotionEvent) {
        // Hold to play at double speed.
        if (player?.isPlaying == true && drag == Drag.NONE) {
          speedHeld = true
          player?.setRate((speed * 2f).coerceAtMost(4f))
          showInfo("⏩ ${speedLabel((speed * 2f).coerceAtMost(4f))}", persistent = true)
        }
      }
    })
    var pinch = 1f
    val pinches = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        pinch = 1f
        return true
      }

      override fun onScale(detector: ScaleGestureDetector): Boolean {
        pinch *= detector.scaleFactor
        return true
      }

      override fun onScaleEnd(detector: ScaleGestureDetector) {
        if (pinch > 1.12f) setScale(SCALE_FILL_SCREEN) else if (pinch < 0.9f) setScale(SCALE_FIT)
      }
    })
    val tapCatcher = View(this)
    touchView = tapCatcher
    tapCatcher.setOnTouchListener { _, event -> onVideoTouch(event, taps, pinches) }
    root.addView(tapCatcher, FrameLayout.LayoutParams(MATCH, MATCH))

    infoText = TextView(this).apply {
      gravity = Gravity.CENTER
      setTextColor(CYAN)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
      typeface = Typeface.DEFAULT_BOLD
      setShadowLayer(16f, 0f, 0f, CYAN)
      setPadding(dp(18), dp(8), dp(18), dp(8))
      background = GradientDrawable().apply {
        cornerRadius = dp(22).toFloat()
        setColor(Color.argb(170, 5, 6, 15))
      }
      visibility = View.GONE
    }
    root.addView(infoText, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

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
      val gap = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) }
      addView(neonButton("▣ PiP") { enterPip() }, gap)
      addView(neonButton("🔒") { lock() }, gap)
    }
    root.addView(topBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

    unlockButton = neonButton("🔓 Unlock") { unlock() }.apply { visibility = View.GONE }
    root.addView(
      unlockButton,
      FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
        topMargin = dp(18)
        leftMargin = dp(18)
      }
    )

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
      textDirection = View.TEXT_DIRECTION_LTR
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
      speedButton = neonButton(speedLabel(speed)) { chooseSpeed() }
      addView(speedButton, gap)
      addView(neonButton("Audio") { chooseAudio() }, gap)
      addView(neonButton("Subtitles") { chooseSubtitles() }, gap)
      scaleButton = neonButton("⛶ ${SCALES[scaleIndex].second}") {
        scaleIndex = (scaleIndex + 1) % SCALES.size
        applyScale()
        poke()
      }
      addView(scaleButton)
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
    private const val PICK_SUBTITLE = 41
    private const val KEY_SCALE = "scale"
    private const val KEY_LANGUAGE = "subtitle_language"
    private const val KEY_SUBDL = "subdl_key"
    private const val KEY_OPENSUBTITLES = "opensubtitles_key"
    private const val KEY_SUBSOURCE = "subsource_key"

    /** Screen modes, in the order the ⛶ button cycles through them. */
    private val SCALES = listOf(
      MediaPlayer.ScaleType.SURFACE_BEST_FIT to "Fit",
      MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to "Fill",
      MediaPlayer.ScaleType.SURFACE_FILL to "Stretch",
      MediaPlayer.ScaleType.SURFACE_16_9 to "16:9",
      MediaPlayer.ScaleType.SURFACE_4_3 to "4:3",
      MediaPlayer.ScaleType.SURFACE_ORIGINAL to "Original",
    )
    private const val SCALE_FIT = 0
    private const val SCALE_FILL_SCREEN = 1

    /** Fill the screen by default: no black bars. */
    private const val DEFAULT_SCALE = SCALE_FILL_SCREEN
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
      // Latin digits, left to right, whatever the phone's language.
      val text = if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
      } else {
        String.format(Locale.US, "%d:%02d", m, s)
      }
      return "\u200E" + text
    }
  }
}
