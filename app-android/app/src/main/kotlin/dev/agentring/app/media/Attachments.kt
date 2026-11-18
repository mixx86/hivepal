package dev.agentring.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import dev.agentring.app.Graph
import dev.agentring.core.Attachment
import java.io.ByteArrayOutputStream
import java.io.File

/** Photos and voice clips (Hivepal): downloaded once into the app's cache, uploaded compressed. */
object Attachments {
    private const val MAX_SIDE = 1600

    private fun dir(context: Context) = File(context.cacheDir, "attachments").apply { mkdirs() }

    /** The file on this phone, downloading it first if needed. Blocking. */
    fun local(context: Context, a: Attachment): File {
        val f = File(dir(context), a.fileId)
        if (f.length() > 0) return f
        val bytes = Graph.api()!!.downloadFile(a.fileId)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(f)
        return f
    }

    /** A bitmap scaled down for the chat. Blocking. */
    fun thumbnail(context: Context, a: Attachment, maxSide: Int = 1024): Bitmap? {
        val f = local(context, a)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** A picked photo as JPEG, at most MAX_SIDE px on its long side. Blocking. */
    fun compressPhoto(context: Context, uri: Uri): ByteArray {
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
        }
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        }
    }

    private val transcripts = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun cachedTranscript(fileId: String): String? = transcripts[fileId]

    fun cacheTranscript(fileId: String, text: String) {
        transcripts[fileId] = text
    }

    /** Remembers an upload we already have locally, so it shows without downloading it again. */
    fun keep(context: Context, fileId: String, bytes: ByteArray) {
        File(dir(context), fileId).writeBytes(bytes)
    }
}
