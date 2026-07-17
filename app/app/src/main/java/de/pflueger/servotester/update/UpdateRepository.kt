package de.pflueger.servotester.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One downloadable file attached to a GitHub release. */
data class ReleaseAsset(val name: String, val url: String, val size: Long)

/** The latest published release of the ServoTester repo. */
data class ReleaseInfo(
    val tag: String,
    val name: String,
    val notes: String,
    val assets: List<ReleaseAsset>,
) {
    /** The app package to install (first `*.apk` asset). */
    val apk: ReleaseAsset? get() = assets.firstOrNull { it.name.endsWith(".apk", true) }

    /**
     * The OTA firmware image — the plain `*.bin`, deliberately NOT the big
     * `*merged.bin` (that one is the full-flash image for a factory-fresh chip
     * and is rejected by the OTA path).
     */
    val firmware: ReleaseAsset? get() =
        assets.firstOrNull { it.name.endsWith(".bin", true) && !it.name.contains("merged", true) }
}

/**
 * Reads GitHub's public Releases API for [repo] and downloads release assets.
 * No auth needed for a public repo; releases are populated by hand.
 */
class UpdateRepository(private val repo: String = "Basti77/servotester") {

    /** @return the latest release, or null if the repo has none published yet. */
    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val conn = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection()
                as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "ServoTester-App")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            when (conn.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> return@withContext null  // no release yet
                HttpURLConnection.HTTP_OK -> Unit
                else -> error("GitHub antwortete mit HTTP ${conn.responseCode}.")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val a = arr.getJSONObject(i)
                    ReleaseAsset(
                        name = a.getString("name"),
                        url = a.getString("browser_download_url"),
                        size = a.optLong("size"),
                    )
                }
            } ?: emptyList()
            ReleaseInfo(
                tag = json.optString("tag_name"),
                name = json.optString("name").ifBlank { json.optString("tag_name") },
                notes = json.optString("body"),
                assets = assets,
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads an asset fully into memory, reporting 0..100 % via [onProgress]. */
    suspend fun download(url: String, onProgress: (Int) -> Unit = {}): ByteArray =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                instanceFollowRedirects = true    // browser_download_url -> CDN
                setRequestProperty("User-Agent", "ServoTester-App")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    error("Download fehlgeschlagen (HTTP ${conn.responseCode}).")
                }
                val total = conn.contentLengthLong
                val out = java.io.ByteArrayOutputStream(
                    if (total > 0) total.toInt() else 1 shl 20)
                conn.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = ((read * 100) / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
                out.toByteArray()
            } finally {
                conn.disconnect()
            }
        }
}
