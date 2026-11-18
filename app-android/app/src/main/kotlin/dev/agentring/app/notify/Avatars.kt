package dev.agentring.app.notify

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.agentring.app.ui.MainActivity

/**
 * Avatars and conversation shortcuts for notifications (Hivepal): with a long-lived shortcut per
 * chat, Android lists our notifications in its "Conversations" section with the chat's avatar.
 * Colours match the app's (ui/Chats.kt).
 */
object Avatars {
    private val PALETTE = intArrayOf(0xFFE8703A.toInt(), 0xFF3B82F6.toInt(), 0xFF10B981.toInt(), 0xFFA855F7.toInt(),
        0xFFEAB308.toInt(), 0xFFEC4899.toInt(), 0xFF14B8A6.toInt(), 0xFF6366F1.toInt())

    private fun colorFor(key: String) = PALETTE[(key.hashCode() and 0x7fffffff) % PALETTE.size]

    fun bitmap(key: String, name: String, size: Int = 192): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = colorFor(key)
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = 0xFFFFFFFF.toInt()
        p.textSize = size * 0.46f
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        val letter = name.trim().take(1).uppercase().ifEmpty { "?" }
        c.drawText(letter, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    fun icon(key: String, name: String): IconCompat = IconCompat.createWithBitmap(bitmap(key, name))

    fun person(key: String, name: String): Person =
        Person.Builder().setName(name).setKey(key).setIcon(icon(key, name)).setImportant(true).build()

    /** Publishes (or refreshes) the conversation shortcut for a chat; returns its id. */
    fun conversationShortcut(context: Context, conv: String, name: String, person: Person): String {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_MESSAGES)
            .putExtra(MainActivity.EXTRA_CONV, conv)
        val shortcut = ShortcutInfoCompat.Builder(context, conv)
            .setShortLabel(name)
            .setLongLived(true)
            .setIntent(intent)
            .setPerson(person)
            .setIcon(icon(conv, name))
            .setCategories(setOf("android.shortcut.conversation"))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
        return conv
    }
}
