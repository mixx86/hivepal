package dev.agentring.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Hivepal: is there a newer build of this app's channel? Asked only when the user taps "Check for
 * updates" (no background calls to GitHub). Releases (`0.7.0`) compare with the latest release.
 * Preview builds (`0.7.0+3.gabc1234`, signed with the debug key like android-preview) compare their
 * commit with the android-preview build's: a different one is newer (it follows main) and installs
 * over this app. Self-built ones with local changes (`….dirty`) aren't compared.
 */
object Updates {
    const val REPO = "mixx86/hivepal"
    const val RELEASES_URL = "https://github.com/$REPO/releases/latest"

    sealed interface Result {
        data class UpToDate(val version: String) : Result
        data class Newer(val version: String, val url: String) : Result
        /** android-preview is at another commit: [url] is its APK. */
        data class NewerPreview(val commit: String, val url: String) : Result
        /** This build isn't a release: updates come from where it came from. */
        data object NotARelease : Result
    }

    @Serializable
    private data class Release(@SerialName("tag_name") val tag: String, @SerialName("html_url") val url: String)

    @Serializable
    private data class Asset(val name: String, @SerialName("browser_download_url") val url: String)

    @Serializable
    private data class Preview(@SerialName("target_commitish") val commit: String, val assets: List<Asset> = emptyList())

    private val PREVIEW_VERSION = Regex("""^\d+\.\d+\.\d+(?:(?:a|b|rc)\d+)?\+\d+\.g([0-9a-f]{7,40})$""")

    /** The commit of a preview build (`0.7.0+3.gabc1234`), or null (a release, or local changes). */
    fun previewCommit(version: String): String? = PREVIEW_VERSION.matchEntire(version.trim())?.groupValues?.get(1)

    fun comparePreview(currentCommit: String, latestCommit: String, apkUrl: String): Result =
        if (latestCommit.startsWith(currentCommit)) Result.UpToDate("preview ${currentCommit.take(7)}")
        else Result.NewerPreview(latestCommit.take(7), apkUrl)

    private val VERSION = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:(a|b|rc)(\d+))?$""")

    /** Sortable form of a release version (the same order release.yml gives versionCode), or null. */
    fun key(version: String): List<Int>? {
        val m = VERSION.matchEntire(version.trim()) ?: return null
        val (major, minor, patch, kind, n) = m.destructured
        val slot = when (kind) { "a" -> n.toInt(); "b" -> 30 + n.toInt(); "rc" -> 60 + n.toInt(); else -> 99 }
        return listOf(major.toInt(), minor.toInt(), patch.toInt(), slot)
    }

    fun compare(current: String, latestTag: String, latestUrl: String): Result {
        val mine = key(current) ?: return Result.NotARelease
        val latest = key(latestTag) ?: return Result.UpToDate(current)
        val newer = latest.zip(mine).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
        return if (newer) Result.Newer(latestTag.removePrefix("v"), latestUrl) else Result.UpToDate(current)
    }

    /** Blocking; call off the main thread. Throws IOException when GitHub can't be reached. */
    fun check(current: String, http: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
              api: String = "https://api.github.com"): Result {
        previewCommit(current)?.let { mine ->
            val req = Request.Builder().url("$api/repos/$REPO/releases/tags/android-preview")
                .header("accept", "application/vnd.github+json").header("user-agent", "hivepal-android").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("GitHub answered ${r.code}")
                val p = ProtocolJson.decodeFromString(Preview.serializer(), r.body!!.string())
                val apk = p.assets.firstOrNull { it.name.endsWith(".apk") }?.url
                    ?: "https://github.com/$REPO/releases/tag/android-preview"
                return comparePreview(mine, p.commit, apk)
            }
        }
        if (key(current) == null) return Result.NotARelease
        val req = Request.Builder().url("$api/repos/$REPO/releases/latest")
            .header("accept", "application/vnd.github+json").header("user-agent", "hivepal-android").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("GitHub answered ${r.code}")
            val rel = ProtocolJson.decodeFromString(Release.serializer(), r.body!!.string())
            return compare(current, rel.tag, rel.url)
        }
    }
}
