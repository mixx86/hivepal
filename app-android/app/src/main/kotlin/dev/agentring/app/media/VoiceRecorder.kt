package dev.agentring.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/** Records a voice note as AAC in an .m4a (audio/mp4), the format the relay takes. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null
    val elapsedMs: Long get() = if (recorder != null) SystemClock.elapsedRealtime() - startedAt else 0L

    fun start() {
        stopQuietly()
        val f = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(64_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(f.path)
        r.prepare()
        r.start()
        recorder = r
        file = f
        startedAt = SystemClock.elapsedRealtime()
    }

    /** Stops and returns the clip and its length, or null if it was too short to keep. */
    fun stop(): Pair<File, Long>? {
        val r = recorder ?: return null
        val f = file
        val ms = elapsedMs
        recorder = null
        file = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (!ok || f == null || ms < 700) {
            f?.delete()
            return null
        }
        return f to ms
    }

    fun cancel() {
        stop()?.first?.delete()
    }

    private fun stopQuietly() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete()
        file = null
    }
}
