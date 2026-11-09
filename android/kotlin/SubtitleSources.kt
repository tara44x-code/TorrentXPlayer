package com.tara44x.torrentxplayer

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/** A subtitle language offered for online search: ISO 639-1 code and display name. */
data class SubLanguage(val code: String, val name: String)

/** What the file name says about the video: its title, year, and episode. */
data class VideoGuess(val title: String, val year: Int?, val season: Int?, val episode: Int?)

/** One online search result; [download] fetches its bytes (a zip or a subtitle file). */
class SubResult(
  val source: String,
  val label: String,
  val language: String,
  val download: () -> ByteArray,
)

/**
 * Online subtitle search across several sites. Podnapisi needs no account; SubDL and
 * OpenSubtitles.com need a free API key each (entered in the player, kept on the device).
 * Every call blocks: run them off the main thread.
 */
object SubtitleSources {
  val LANGUAGES = listOf(
    SubLanguage("ar", "العربية (Arabic)"),
    SubLanguage("en", "English"),
    SubLanguage("fr", "Français (French)"),
    SubLanguage("es", "Español (Spanish)"),
    SubLanguage("tr", "Türkçe (Turkish)"),
    SubLanguage("fa", "فارسی (Persian)"),
    SubLanguage("ku", "Kurdî (Kurdish)"),
    SubLanguage("de", "Deutsch (German)"),
    SubLanguage("it", "Italiano (Italian)"),
    SubLanguage("pt", "Português (Portuguese)"),
    SubLanguage("ru", "Русский (Russian)"),
    SubLanguage("hi", "हिन्दी (Hindi)"),
    SubLanguage("ur", "اردو (Urdu)"),
    SubLanguage("id", "Indonesia (Indonesian)"),
    SubLanguage("ms", "Melayu (Malay)"),
    SubLanguage("nl", "Nederlands (Dutch)"),
    SubLanguage("el", "Ελληνικά (Greek)"),
    SubLanguage("he", "עברית (Hebrew)"),
  )

  private const val USER_AGENT = "TorrentXPlayer v1.0"
  private const val MAX_BYTES = 8 * 1024 * 1024
  private val SUBTITLE_EXTENSIONS = listOf("srt", "ass", "ssa", "vtt", "sub")

  // ---- Guessing the title from the file name ------------------------------------------------

  fun guess(fileName: String): VideoGuess {
    var name = fileName.trim()
    val dot = name.lastIndexOf('.')
    if (dot > 0 && name.length - dot <= 5) name = name.substring(0, dot)
    name = name.replace(Regex("\\[[^\\]]*\\]"), " ").replace(Regex("[._]+"), " ")
    val episode = Regex("(?i)\\bS(\\d{1,2})\\s*E(\\d{1,3})\\b").find(name)
    val year = Regex("\\b(19\\d{2}|20\\d{2})\\b").findAll(name).firstOrNull { it.range.first > 0 }
    val tag = Regex(
      "(?i)\\b(2160p|1080p|720p|480p|4k|bluray|blu ray|brrip|bdrip|web ?dl|webrip|hdrip|dvdrip|hdtv|x264|x265|h264|h265|hevc|aac|ac3|dts|remux|proper|repack|extended|unrated)\\b"
    ).find(name)
    val cut = listOfNotNull(episode?.range?.first, year?.range?.first, tag?.range?.first)
      .filter { it > 0 }
      .minOrNull() ?: name.length
    val title = name.substring(0, cut)
      .replace(Regex("[(\\-]+\\s*$"), " ")
      .replace(Regex("\\s+"), " ")
      .trim()
    return VideoGuess(
      title = title.ifEmpty { name.trim() },
      year = year?.value?.toIntOrNull(),
      season = episode?.groupValues?.get(1)?.toIntOrNull(),
      episode = episode?.groupValues?.get(2)?.toIntOrNull(),
    )
  }

  // ---- Search -------------------------------------------------------------------------------

  /** Searches every source that can be used; returns the results and the errors per source. */
  fun search(
    guess: VideoGuess,
    language: String,
    subdlKey: String?,
    openSubtitlesKey: String?,
  ): Pair<List<SubResult>, List<String>> {
    val jobs = mutableListOf<Pair<String, () -> List<SubResult>>>()
    jobs += "Podnapisi" to { podnapisi(guess, language) }
    if (!subdlKey.isNullOrBlank()) jobs += "SubDL" to { subdl(guess, language, subdlKey.trim()) }
    if (!openSubtitlesKey.isNullOrBlank()) {
      jobs += "OpenSubtitles" to { openSubtitles(guess, language, openSubtitlesKey.trim()) }
    }
    val results = arrayOfNulls<List<SubResult>>(jobs.size)
    val errors = arrayOfNulls<String>(jobs.size)
    val threads = jobs.mapIndexed { index, (name, job) ->
      Thread {
        try {
          results[index] = job()
        } catch (e: Exception) {
          errors[index] = "$name: ${e.message ?: e.javaClass.simpleName}"
        }
      }.also { it.start() }
    }
    threads.forEach { it.join(45_000) }
    return results.filterNotNull().flatten() to errors.filterNotNull()
  }

  private fun podnapisi(guess: VideoGuess, language: String): List<SubResult> {
    val params = mutableListOf("keywords" to guess.title, "language" to language)
    if (guess.season != null && guess.episode != null) {
      params += "seasons" to guess.season.toString()
      params += "episodes" to guess.episode.toString()
      params += "movie_type" to "tv-series"
    } else {
      params += "movie_type" to "movie"
      guess.year?.let { params += "year" to it.toString() }
    }
    val body = http(
      "https://www.podnapisi.net/subtitles/search/advanced?" + query(params),
      headers = mapOf("Accept" to "application/json"),
    )
    val data = JSONObject(String(body, Charsets.UTF_8)).optJSONArray("data") ?: JSONArray()
    return (0 until data.length()).mapNotNull { i ->
      val item = data.optJSONObject(i) ?: return@mapNotNull null
      val id = item.optString("id").ifEmpty { return@mapNotNull null }
      val releases = item.optJSONArray("releases")
      val movie = item.optJSONObject("movie")
      val label = releases?.optString(0)?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(movie?.optString("title"), movie?.optString("year")).joinToString(" ")
      SubResult("Podnapisi", label, item.optString("language", language)) {
        http("https://www.podnapisi.net/subtitles/$id/download?container=zip")
      }
    }
  }

  private fun subdl(guess: VideoGuess, language: String, key: String): List<SubResult> {
    val params = mutableListOf(
      "api_key" to key,
      "film_name" to guess.title,
      "languages" to language.uppercase(),
      "subs_per_page" to "30",
    )
    if (guess.season != null && guess.episode != null) {
      params += "type" to "tv"
      params += "season_number" to guess.season.toString()
      params += "episode_number" to guess.episode.toString()
    } else {
      params += "type" to "movie"
      guess.year?.let { params += "year" to it.toString() }
    }
    val body = http("https://api.subdl.com/api/v1/subtitles?" + query(params))
    val json = JSONObject(String(body, Charsets.UTF_8))
    if (!json.optBoolean("status", true)) throw IOException(json.optString("error", "search failed"))
    val subs = json.optJSONArray("subtitles") ?: JSONArray()
    return (0 until subs.length()).mapNotNull { i ->
      val item = subs.optJSONObject(i) ?: return@mapNotNull null
      val path = item.optString("url").ifEmpty { return@mapNotNull null }
      val label = item.optString("release_name").ifEmpty { item.optString("name") }
      SubResult("SubDL", label, item.optString("lang", language)) {
        http("https://dl.subdl.com$path")
      }
    }
  }

  private fun openSubtitles(guess: VideoGuess, language: String, key: String): List<SubResult> {
    val headers = mapOf("Api-Key" to key, "Accept" to "application/json")
    val params = mutableListOf<Pair<String, String>>()
    if (guess.episode != null) params += "episode_number" to guess.episode.toString()
    params += "languages" to language
    params += "query" to guess.title.lowercase()
    if (guess.season != null) params += "season_number" to guess.season.toString()
    if (guess.season == null) guess.year?.let { params += "year" to it.toString() }
    val body = http("https://api.opensubtitles.com/api/v1/subtitles?" + query(params), headers = headers)
    val data = JSONObject(String(body, Charsets.UTF_8)).optJSONArray("data") ?: JSONArray()
    return (0 until data.length()).mapNotNull { i ->
      val attributes = data.optJSONObject(i)?.optJSONObject("attributes") ?: return@mapNotNull null
      val file = attributes.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
      val fileId = file.optLong("file_id", 0L).takeIf { it > 0 } ?: return@mapNotNull null
      val label = attributes.optString("release").ifEmpty { file.optString("file_name") }
      SubResult("OpenSubtitles", label, attributes.optString("language", language)) {
        val answer = http(
          "https://api.opensubtitles.com/api/v1/download",
          method = "POST",
          headers = headers + ("Content-Type" to "application/json"),
          body = JSONObject().put("file_id", fileId).toString(),
        )
        val link = JSONObject(String(answer, Charsets.UTF_8)).optString("link")
        if (link.isEmpty()) throw IOException("no download link (daily limit reached?)")
        http(link)
      }
    }
  }

  // ---- Turning downloads into a subtitle file -----------------------------------------------

  /**
   * The subtitle inside [bytes] (a zip or a plain subtitle file) as UTF-8 text, with its file
   * extension. Text that is not UTF-8 is read with the usual Windows code page for [language]
   * (Windows-1256 for Arabic, so Arabic subtitles do not come out garbled).
   */
  fun extract(bytes: ByteArray, nameHint: String, language: String): Pair<ByteArray, String> {
    var data = bytes
    var name = nameHint
    if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
      var best: Pair<String, ByteArray>? = null
      ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          val ext = entry.name.substringAfterLast('.', "").lowercase()
          if (!entry.isDirectory && ext in SUBTITLE_EXTENSIONS) {
            val content = readCapped(zip)
            // Prefer .srt, then the first subtitle file found.
            if (best == null || (ext == "srt" && !best!!.first.endsWith(".srt", true))) {
              best = entry.name to content
            }
          }
        }
      }
      val found = best ?: throw IOException("no subtitle file in the archive")
      name = found.first
      data = found.second
    }
    val ext = name.substringAfterLast('.', "srt").lowercase().takeIf { it in SUBTITLE_EXTENSIONS } ?: "srt"
    return toUtf8(data, language) to ext
  }

  fun toUtf8(bytes: ByteArray, language: String): ByteArray {
    val utf8 = Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
      utf8.decode(ByteBuffer.wrap(bytes))
      bytes
    } catch (e: CharacterCodingException) {
      val codePage = when (language.lowercase().take(2)) {
        "ar", "fa", "ur", "ku" -> "windows-1256"
        "ru", "uk", "bg", "sr" -> "windows-1251"
        "tr" -> "windows-1254"
        "el" -> "windows-1253"
        "he" -> "windows-1255"
        else -> "windows-1252"
      }
      String(bytes, Charset.forName(codePage)).toByteArray(Charsets.UTF_8)
    }
  }

  // ---- HTTP ---------------------------------------------------------------------------------

  private fun query(params: List<Pair<String, String>>): String =
    params.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

  fun http(
    url: String,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
  ): ByteArray {
    var target = url
    repeat(5) {
      val connection = URL(target).openConnection() as HttpURLConnection
      try {
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        if (body != null) {
          connection.doOutput = true
          connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        if (code in 300..399) {
          val location = connection.getHeaderField("Location") ?: throw IOException("HTTP $code")
          target = URL(URL(target), location).toString()
          return@repeat
        }
        if (code !in 200..299) {
          val detail = connection.errorStream?.use { String(readCapped(it), Charsets.UTF_8) }
            ?.take(160)?.let { " $it" } ?: ""
          throw IOException("HTTP $code$detail")
        }
        return connection.inputStream.use { readCapped(it) }
      } finally {
        connection.disconnect()
      }
    }
    throw IOException("too many redirects")
  }

  private fun readCapped(input: java.io.InputStream): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
      val n = input.read(buffer)
      if (n < 0) break
      out.write(buffer, 0, n)
      if (out.size() > MAX_BYTES) throw IOException("file too large")
    }
    return out.toByteArray()
  }
}
