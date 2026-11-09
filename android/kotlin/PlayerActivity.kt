package com.tara44x.torrentxplayer

import android.app.Activity
import android.app.AlertDialog
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
  private var videoTitle = ""
  private var scaleIndex = DEFAULT_SCALE
  private lateinit var prefs: SharedPreferences
  private lateinit var scaleButton: TextView
  private lateinit var infoText: TextView
  private val hideInfo = Runnable { infoText.visibility = View.GONE }

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
    scaleIndex = prefs.getInt(KEY_SCALE, DEFAULT_SCALE).coerceIn(0, SCALES.size - 1)
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
    resumeAfterPause = player?.isPlaying == true
    player?.pause()
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
    val checked = tracks.indexOfFirst { it.id == current }
    dialog()
      .setTitle("Subtitles")
      .setSingleChoiceItems(items.toTypedArray(), checked) { box, which ->
        box.dismiss()
        when {
          which < firstAction -> mediaPlayer.setSpuTrack(tracks[which].id)
          which == firstAction -> pickSubtitleFile()
          else -> searchOnline()
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
    if (!prefs.getString(KEY_OPENSUBTITLES, null).isNullOrBlank()) sources += "OpenSubtitles"
    val details = listOfNotNull(
      guess.year?.let { "Year $it" },
      if (guess.season != null && guess.episode != null) "S${guess.season} E${guess.episode}" else null,
    ).joinToString(" · ")
    val note = TextView(ctx).apply {
      text = buildString {
        if (details.isNotEmpty()) append(details).append('\n')
        append("Sources: ").append(sources.joinToString(", "))
        if (sources.size < 3) append("\nAdd free API keys for SubDL and OpenSubtitles to search more sites.")
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
    Thread {
      val (results, errors) = SubtitleSources.search(guess, language, subdlKey, openSubtitlesKey)
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
    layout.addView(subdl)
    layout.addView(openSubtitles)
    builder
      .setTitle("Subtitle site keys (free)")
      .setView(layout)
      .setPositiveButton("Save") { _, _ ->
        prefs.edit()
          .putString(KEY_SUBDL, subdl.text.toString().trim())
          .putString(KEY_OPENSUBTITLES, openSubtitles.text.toString().trim())
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
        setScale(if (scaleIndex == SCALE_FILL_SCREEN) SCALE_FIT else SCALE_FILL_SCREEN)
        return true
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
    tapCatcher.setOnTouchListener { _, event ->
      pinches.onTouchEvent(event)
      if (!pinches.isInProgress) taps.onTouchEvent(event)
      true
    }
    root.addView(tapCatcher, FrameLayout.LayoutParams(MATCH, MATCH))

    infoText = TextView(this).apply {
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
      return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
  }
}
