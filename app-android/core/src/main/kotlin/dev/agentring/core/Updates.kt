package dev.agentring.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Hivepal: is there a newer release than this app? Asked only when the user taps "Check for
 * updates" (no background calls to GitHub). Preview builds (`0.5.0+3.gabc1234`) and self-built ones
 * are another channel, signed with another key: a release can't install over them, so they're not
 * compared.
 */
object Updates {
    const val REPO = "mixx86/hivepal"
    const val RELEASES_URL = "https://github.com/$REPO/releases/latest"

    sealed interface Result {
        data class UpToDate(val version: String) : Result
        data class Newer(val version: String, val url: String) : Result
        /** This build isn't a release: updates come from where it came from. */
        data object NotARelease : Result
    }

    @Serializable
    private data class Release(@SerialName("tag_name") val tag: String, @SerialName("html_url") val url: String)

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
